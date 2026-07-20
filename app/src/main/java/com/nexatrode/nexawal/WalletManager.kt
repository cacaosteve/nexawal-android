package com.nexatrode.nexawal

import android.util.Log

import android.content.Context
import com.nexatrode.nexawal.logic.SendGate
import com.nexatrode.nexawal.logic.SendSafety
import com.nexatrode.nexawal.walletcore.WalletCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android WalletManager that:
 * - Owns a single wallet instance for the app lifecycle (survives screen changes)
 * - Imports/exports walletcore cache for fast resume
 * - Starts refresh via walletcore's async primitive and polls syncStatus (Option 2)
 *
 * Notes:
 * - This is intentionally "manager-ish" and not a ViewModel; you can wrap/own it from a ViewModel later.
 * - This manager is mainnet-only for now (matches your current iOS behavior).
 * - Cache persistence is best-effort: failures are logged into state but do not crash.
 *
 * Single-wallet persistence (Android parity with iOS):
 * - We treat one wallet slot as authoritative (default walletId: "main_wallet").
 * - We persist:
 *     - settings.json (node URL) — safe to exist even before any wallet is created/imported
 *     - metadata.json (Keystore-encrypted mnemonic + restore height + network flag + node URL)
 *     - <walletId>.cache — walletcore cache blob
 */
class WalletManager(
    private val appContext: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Serializable
    data class ReceiveSubaddressEntry(
        val accountIndex: Int = 0,
        val subaddressIndex: Int,
        val label: String = "",
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    @Serializable
    data class ReceiveSubaddressBook(
        val accountIndex: Int = 0,
        val nextSubaddressIndex: Int = 1,
        val entries: List<ReceiveSubaddressEntry> = emptyList(),
    )

    data class UiState(
        val walletId: String? = null,

        /**
         * The configured node URL (persisted).
         *
         * This is intentionally user-editable via Settings, and is used by:
         * - refresh
         * - fee preview / send / sweep
         * - suggested restore height fetch
         */
        val nodeUrl: String? = null,

        /**
         * Primary wallet address (account 0, subaddress 0).
         *
         * Derived from the mnemonic at open time and cached in state for UI display.
         */
        val walletAddress: String? = null,

        val refreshInProgress: Boolean = false,
        val refreshStartedAtMs: Long? = null,
        val refreshLastProgressAtMs: Long? = null,
        val lastError: String? = null,
        val version: String? = null,
        val syncStatus: WalletCore.SyncStatus? = null,

        /**
         * iOS-like stable refresh target height:
         * - null until a refresh has captured a target height
         * - set once at refresh start (when chainHeight exceeds restoreHeight)
         * - cleared when refresh completes/cancels/fails
         */
        val refreshTargetHeight: Long? = null,

        /**
         * Scan tuning (iOS parity):
         * - gapLimit: subaddress minor lookahead per account
         * - accountGap: account lookahead (major indices)
         *
         * These are persisted via [MoneroConfig] and applied at refresh start.
         */
        val gapLimit: Int? = null,
        val accountGap: Int? = null,

        val cacheInfo: CacheInfo? = null,

        // Wallet tab data
        val balance: WalletCore.Balance? = null,

        // Transfers
        val transfersJson: String? = null,
        val transfers: List<Transfer> = emptyList(),
        val transfersParseError: String? = null,

        val lastBalanceRefreshAtMs: Long? = null,
        val lastTransfersRefreshAtMs: Long? = null,
        val balanceIsStaleWhileSyncing: Boolean = false,

        // Send / sweep results (last-known)
        val lastFeePreview: SendJson.FeeResult? = null,
        val lastSendResult: SendJson.SendResult? = null,
        val lastSweepPreview: SendJson.SweepPreviewResult? = null,
        val lastSweepSendResult: SendJson.SweepSendResult? = null,
        val lastSendOrSweepAtMs: Long? = null,

        // Persistence helpers for the "single wallet" slot
        val hasStoredWallet: Boolean? = null,
        val lastLoadedFromDiskAtMs: Long? = null,
        val lastPersistedAtMs: Long? = null,
    )

    data class CacheInfo(
        val filePath: String,
        val bytesOnDisk: Long,
        val lastSavedAtMs: Long,
    )

    /**
     * Minimal metadata stored on disk for the single-wallet slot.
     *
     * Mnemonic is stored as an Android Keystore-encrypted blob, not plaintext.
     */
    data class StoredWalletMetadata(
        val walletId: String = DEFAULT_WALLET_ID,
        val mnemonic: String,
        val restoreHeight: Long,
        val mainnet: Boolean,
        val nodeUrl: String,
        val savedAtMs: Long,
    )

    /**
     * Persisted app-level settings (safe to exist before a wallet is created/imported).
     */
    data class StoredSettings(
        val nodeUrl: String,
        val savedAtMs: Long,
    )

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private val refreshCancelRequested = AtomicBoolean(false)
    private val refreshInProgress = AtomicBoolean(false)
    private val sendGate = SendGate()

    // Cache export throttling:
    // - avoid redundant writes when exportCache returns identical bytes repeatedly (common during steady-state polling)
    // - avoid back-to-back writes within a short time window
    @Volatile private var lastExportCacheHash: Int? = null
    @Volatile private var lastExportCacheLen: Int? = null
    @Volatile private var lastExportAtMs: Long = 0L

    private val transfersJsonParser: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
    private val receiveBookJson: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Default node URL (matches iOS default behavior).
     *
     * Public TLS node for non-local testing.
     */
    fun defaultNodeUrl(): String = "http://127.0.0.1:18092"

    /**
     * Effective node URL to use for walletcore operations.
     *
     * If the user has edited Settings, state.nodeUrl will take precedence.
     */
    fun currentNodeUrl(): String = _state.value.nodeUrl ?: defaultNodeUrl()

    private fun resolveBroadcastNodeUrl(): String {
        return MoneroConfig.broadcastNodeUrl(appContext, currentNodeUrl())
    }

    private fun resolveScanNodeUrl(): String {
        return MoneroConfig.scanNodeUrl(appContext, currentNodeUrl())
    }

    private fun applyProxyEnv(forBroadcast: Boolean) {
        val useProxy = MoneroConfig.shouldUseI2pHttpProxy(appContext, forBroadcast = forBroadcast)
        val proxy = MoneroConfig.i2pHttpProxyAddress(appContext)
        if (useProxy && !proxy.isNullOrBlank()) {
            val proxyUrl = if (proxy.startsWith("http://") || proxy.startsWith("https://")) proxy else "http://$proxy"
            runCatching {
                android.system.Os.setenv("HTTP_PROXY", proxyUrl, true)
                android.system.Os.setenv("http_proxy", proxyUrl, true)
                android.system.Os.setenv("ALL_PROXY", proxyUrl, true)
                android.system.Os.setenv("all_proxy", proxyUrl, true)
            }
        } else {
            runCatching {
                android.system.Os.unsetenv("HTTP_PROXY")
                android.system.Os.unsetenv("http_proxy")
                android.system.Os.unsetenv("ALL_PROXY")
                android.system.Os.unsetenv("all_proxy")
            }
        }
    }

    private fun parseProxyHostPort(raw: String): Pair<String, Int>? {
        val trimmed = raw.trim()
            .removePrefix("http://")
            .removePrefix("https://")
        val parts = trimmed.split(":")
        if (parts.size != 2) return null
        val host = parts[0].trim()
        val port = parts[1].trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port <= 0) return null
        return host to port
    }

    private fun buildDaemonHttpClient(forBroadcast: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)

        if (MoneroConfig.shouldUseI2pHttpProxy(appContext, forBroadcast = forBroadcast)) {
            val proxy = MoneroConfig.i2pHttpProxyAddress(appContext)
            val hostPort = proxy?.let { parseProxyHostPort(it) }
            if (hostPort != null) {
                builder.proxy(
                    java.net.Proxy(
                        java.net.Proxy.Type.HTTP,
                        java.net.InetSocketAddress(hostPort.first, hostPort.second),
                    )
                )
            }
        }
        return builder.build()
    }

    private suspend fun <T> withSendLock(block: suspend () -> T): T {
        if (!sendGate.tryBegin()) {
            throw IllegalStateException(SendGate.ALREADY_IN_PROGRESS)
        }
        return try {
            block()
        } finally {
            sendGate.end()
        }
    }

    /**
     * Cuprate (:18092) sibling Monero RPC (:18081) retry for fee_rate failures that clearly
     * happened before spend/broadcast (mirrors iOS WalletManager).
     */
    private fun <T> withOptionalSiblingFeeRetry(nodeUrl: String, op: (String) -> T): T {
        return try {
            op(nodeUrl)
        } catch (t: Throwable) {
            val coreMsg = runCatching { WalletCore.lastErrorMessage() }.getOrNull().orEmpty()
            val fallback = SendSafety.shouldRetryViaSiblingMonerod(
                errorText = t.message.orEmpty(),
                coreMessage = coreMsg,
                endpoint = nodeUrl,
            ) ?: throw t
            Log.i(
                "WalletManager",
                "Cuprate fee RPC unavailable at $nodeUrl; retrying via sibling Monero RPC $fallback",
            )
            op(fallback)
        }
    }

    private fun filterJsonForSubaddressMinor(minor: Int): String {
        require(minor >= 0) { "subaddress minor must be >= 0" }
        return JSONObject()
            .put("subaddress_minor", minor)
            .toString()
    }

    private fun migrateLegacyDefaultNodeUrl(nodeUrl: String): String {
        return when (normalizeNodeUrl(nodeUrl)) {
            "https://node.sethforprivacy.com:443",
            "https://node.monerod.org:443",
            "http://192.168.4.137:18081",
            "http://10.0.2.2:18081" -> defaultNodeUrl()
            else -> normalizeNodeUrl(nodeUrl)
        }
    }

    /**
     * Normalize user input into a URL string that walletcore expects.
     *
     * Accepts:
     * - "10.0.2.2:18092" -> "http://10.0.2.2:18092"
     * - "node.example.com:443" -> "https://node.example.com:443"
     * - "http://10.0.2.2:18092" (unchanged)
     * - "https://example.com:18092" (unchanged)
     *
     * Trims whitespace and rejects blank values.
     */
    private fun normalizeNodeUrl(raw: String): String {
        val s = raw.trim()
        require(s.isNotEmpty()) { "node URL must not be empty" }

        return if (s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)) {
            s
        } else if (s.endsWith(":443")) {
            "https://$s"
        } else {
            "http://$s"
        }
    }

    /**
     * Load persisted app settings (node URL) into state.
     *
     * Call this very early (e.g. app launch) so WalletCreationScreen uses the saved node.
     */
    suspend fun loadSettingsOnLaunch(): Boolean = withContext(ioDispatcher) {
        val f = settingsFile()
        if (!f.exists()) {
            return@withContext false
        }

        val s = runCatching { readSettings() }.getOrNull() ?: return@withContext false
        _state.value = _state.value.copy(nodeUrl = s.nodeUrl, lastError = null, lastPersistedAtMs = s.savedAtMs)
        true
    }

    /**
     * Update the configured node URL in memory and persist it even if no wallet exists yet.
     *
     * This is what the Settings screen should call.
     */
    suspend fun setNodeUrl(newNodeUrl: String) = withContext(ioDispatcher) {
        val normalized = normalizeNodeUrl(newNodeUrl)

        // Update state immediately so UI and flows use it.
        _state.value = _state.value.copy(nodeUrl = normalized, lastError = null)

        // Persist to settings.json (works even before a wallet exists).
        persistSettings(
            StoredSettings(
                nodeUrl = normalized,
                savedAtMs = System.currentTimeMillis(),
            )
        )

        // Best-effort: if metadata exists, update the stored nodeUrl too so it persists alongside the wallet slot.
        runCatching {
            val f = metadataFile()
            if (!f.exists()) return@runCatching

            val meta = readMetadata()
            persistMetadata(
                meta.copy(
                    nodeUrl = normalized,
                    savedAtMs = System.currentTimeMillis()
                )
            )
        }.onFailure { t ->
            _state.value = _state.value.copy(lastError = "Failed to persist node URL: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /**
     * Default walletId used by the single-wallet UX.
     */
    fun defaultWalletId(): String = DEFAULT_WALLET_ID

    /**
     * Call once early (e.g., app start) to set a readable version string in state.
     */
    fun loadVersion() {
        val v = runCatching { WalletCore.version() }.getOrNull()
        _state.value = _state.value.copy(version = v)
    }

    /**
     * Best-effort: derive and store the primary address into state.
     *
     * This is used for iOS parity and for Settings->Debug. If derivation fails we:
     * - log a clear diagnostic
     * - store a human-readable lastError (non-fatal)
     */
    private suspend fun deriveAndStorePrimaryAddressBestEffort(
        mnemonic: String,
        mainnet: Boolean,
    ) {
        val result = withContext(ioDispatcher) {
            runCatching {
                WalletCore.derivePrimaryAddressFromMnemonic(
                    mnemonic = mnemonic.trim(),
                    mainnet = mainnet
                )
            }
        }
        val derived = result.onFailure { t ->
            Log.w(
                "WalletManager",
                "derivePrimaryAddressFromMnemonic failed: ${t.message ?: t.toString()}"
            )
            Log.w("WalletManager", "derivePrimaryAddressFromMnemonic throwable=$t")
            _state.value = _state.value.copy(
                lastError = "Failed to derive primary address: ${t.message ?: t.toString()}"
            )
        }.getOrNull()

        if (!derived.isNullOrBlank()) {
            _state.value = _state.value.copy(walletAddress = derived)
        }
    }

    /**
     * Public debug helper: recompute derived address from stored metadata (if any).
     * This is useful when state.walletAddress is missing due to a previous derivation failure.
     */
    fun recomputeDerivedAddressFromStoredMetadata() {
        scope.launch(ioDispatcher) {
            val f = metadataFile()
            if (!f.exists()) {
                _state.value = _state.value.copy(lastError = "No stored wallet metadata found")
                return@launch
            }

            val meta = runCatching { readMetadata() }.getOrNull()
            if (meta == null) {
                _state.value = _state.value.copy(lastError = "Failed to read stored wallet metadata")
                return@launch
            }

            deriveAndStorePrimaryAddressBestEffort(
                mnemonic = meta.mnemonic,
                mainnet = meta.mainnet
            )
        }
    }

    /**
     * Returns true if a wallet is persisted on device (metadata exists).
     *
     * This mirrors iOS `WalletViewModel.hasStoredWallet()`.
     */
    suspend fun hasStoredWallet(): Boolean = withContext(ioDispatcher) {
        metadataFile().exists()
    }

    suspend fun loadReceiveSubaddressBook(): ReceiveSubaddressBook = withContext(ioDispatcher) {
        val existing = runCatching {
            val f = receiveSubaddressesFile()
            if (!f.exists()) null else receiveBookJson.decodeFromString<ReceiveSubaddressBook>(f.readText())
        }.getOrNull()

        val normalized = normalizeReceiveBook(existing)
        persistReceiveSubaddressBook(normalized)
        normalized
    }

    suspend fun createReceiveSubaddress(label: String = ""): ReceiveSubaddressEntry = withContext(ioDispatcher) {
        val current = loadReceiveSubaddressBook()
        val nextIndex = current.nextSubaddressIndex.coerceAtLeast(1)
        val entry = ReceiveSubaddressEntry(
            accountIndex = 0,
            subaddressIndex = nextIndex,
            label = label.trim(),
            createdAtMs = System.currentTimeMillis(),
        )
        val updated = normalizeReceiveBook(
            current.copy(
                nextSubaddressIndex = nextIndex + 1,
                entries = current.entries + entry
            )
        )
        persistReceiveSubaddressBook(updated)
        entry
    }

    suspend fun deriveReceiveAddress(subaddressIndex: Int): String = withContext(ioDispatcher) {
        val meta = readMetadata()
        WalletCore.deriveSubaddressFromMnemonic(
            mnemonic = meta.mnemonic,
            accountIndex = 0,
            subaddressIndex = subaddressIndex,
            mainnet = meta.mainnet
        )
    }

    /**
     * Open/register a wallet from mnemonic and attempt to import persisted cache if present.
     *
     * Cache import is best-effort:
     * - If import fails, we keep going (wallet can still refresh from chain).
     *
     * Node URL persistence:
     * - If you pass nodeUrl, we store it in state and persist it to metadata.
     */
    suspend fun openWalletFromMnemonic(
        walletId: String,
        mnemonic: String,
        restoreHeight: Long = 0L,
        nodeUrl: String = currentNodeUrl(),
        mainnet: Boolean = true,
        persist: Boolean = true,
        replaceExisting: Boolean = false,
    ) {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        require(mnemonic.isNotBlank()) { "mnemonic must not be blank" }

        val normalizedNodeUrl = normalizeNodeUrl(nodeUrl)

        if (replaceExisting) {
            clearStoredWallet()
        }

        withContext(ioDispatcher) {
            WalletCore.openFromMnemonic(
                walletId = walletId,
                mnemonic = mnemonic.trim(),
                restoreHeight = restoreHeight,
                mainnet = mainnet,
            )
        }

        // Derive and cache primary address for UI (iOS parity with WalletView.walletAddress).
        // If derivation fails, log and surface it (non-fatal) instead of silently producing null.
        val derivedAddress: String? = withContext(ioDispatcher) {
            runCatching {
                WalletCore.derivePrimaryAddressFromMnemonic(
                    mnemonic = mnemonic.trim(),
                    mainnet = mainnet
                )
            }
        }.onFailure { t ->
            Log.w(
                "WalletManager",
                "derivePrimaryAddressFromMnemonic (openWalletFromMnemonic) failed: ${t.message ?: t.toString()}"
            )
            Log.w("WalletManager", "derivePrimaryAddressFromMnemonic (openWalletFromMnemonic) throwable=$t")
            _state.value = _state.value.copy(
                lastError = "Failed to derive primary address: ${t.message ?: t.toString()}"
            )
        }.getOrNull()

        _state.value = _state.value.copy(
            walletId = walletId,
            nodeUrl = normalizedNodeUrl,
            walletAddress = derivedAddress,
            hasStoredWallet = true,
            lastLoadedFromDiskAtMs = System.currentTimeMillis(),
            lastError = null,
        )

        // Best-effort retry if we ended up with no address in state.
        if (derivedAddress.isNullOrBlank()) {
            deriveAndStorePrimaryAddressBestEffort(
                mnemonic = mnemonic,
                mainnet = mainnet
            )
        }

        // Best-effort: import cache from disk if present.
        withContext(ioDispatcher) {
            importCacheIfPresent(walletId)
        }
        // Refresh status snapshot after open/import.
        updateStatusSnapshot(walletId)

        // Persist metadata for auto-load on next launch.
        if (persist) {
            persistMetadata(
                StoredWalletMetadata(
                    walletId = walletId,
                    mnemonic = mnemonic.trim(),
                    restoreHeight = restoreHeight,
                    mainnet = mainnet,
                    nodeUrl = normalizedNodeUrl,
                    savedAtMs = System.currentTimeMillis(),
                )
            )
        }

        _state.value = _state.value.copy(hasStoredWallet = runCatching { hasStoredWallet() }.getOrNull())
    }


    /// ... (rest of file unchanged)
    /**
     * Start (or join) an in-flight refresh.
     *
     * Behavior:
     * - If refresh already in progress, this waits for it to finish and returns the latest status.
     * - Otherwise:
     *   - triggers walletcore async refresh (wallet_refresh_async)
     *   - polls wallet_sync_status every ~200ms until completion or cancellation
     *   - exports cache periodically and at the end (best-effort)
     */
    suspend fun refreshWallet(): WalletCore.SyncStatus {
        val walletId = _state.value.walletId ?: throw IllegalStateException("No wallet open")
        val nodeUrl = resolveScanNodeUrl()
        applyProxyEnv(forBroadcast = false)

        // iOS parity: load persisted scan tuning defaults (gap limit + account lookahead).
        // Apply these to walletcore at refresh start:
        // - gapLimit via wallet_set_gap_limit
        // - accountGap via WALLETCORE_ACCOUNT_GAP env var
        val cfg = MoneroConfig.snapshot(appContext)
        val gapLimit = cfg.gapLimit
        val accountGap = cfg.accountGap

        // Mirror iOS: log the node URL at refresh start so we can see exactly what the core is using.
        Log.i("WalletManager", "🌐 Refresh starting with nodeURL=$nodeUrl walletId=$walletId policy=${cfg.networkPolicy}")

        // Dev probe: fetch /get_height via OkHttp so we can compare emulator network height vs wallet core status.
        // Important: run this on IO to avoid NetworkOnMainThreadException.
        val probedDaemonHeight: Long? = withContext(ioDispatcher) {
            runCatching { probeDaemonHeightViaOkHttp(nodeUrl) }
                .onFailure { t ->
                    Log.w("WalletManager", "OkHttp daemon /get_height probe failed: ${t.message ?: t.javaClass.simpleName}")
                }
                .getOrNull()
        }

        if (probedDaemonHeight != null) {
            Log.i("WalletManager", "OkHttp daemon /get_height height=$probedDaemonHeight (nodeURL=$nodeUrl)")
        }

        // Snapshot status before refresh starts (helps diagnose daemon connectivity / cleartext issues).
        withContext(ioDispatcher) {
            runCatching { WalletCore.syncStatus(walletId) }
        }
            .onSuccess { st ->
                Log.i(
                    "WalletManager",
                    "Refresh preflight syncStatus: chainHeight=${st.chainHeight} lastScanned=${st.lastScanned} restoreHeight=${st.restoreHeight} chainTime=${st.chainTime} lastRefreshTimestamp=${st.lastRefreshTimestamp}"
                )
            }
            .onFailure { t ->
                Log.w("WalletManager", "Refresh preflight syncStatus failed: ${t.message ?: t.javaClass.simpleName}")
            }

        // Core-side last error message (best-effort). This is especially useful when async refresh workers fail silently.
        withContext(ioDispatcher) {
            runCatching { WalletCore.lastErrorMessage() }
        }
            .onSuccess { msg ->
                if (!msg.isNullOrBlank()) {
                    Log.w("WalletManager", "Core lastErrorMessage (preflight): $msg")
                }
            }
            .onFailure { t ->
                Log.w("WalletManager", "Core lastErrorMessage (preflight) probe failed: ${t.message ?: t.javaClass.simpleName}")
            }

        // iOS-style single-flight:
        // - If there is a running refresh job, do NOT start another core refresh.
        // - Instead, just await the in-flight job and return the latest status.
        refreshJob?.let { job ->
            if (job.isActive) {
                Log.i("WalletManager", "Refresh already in progress; joining existing job walletId=$walletId")
                job.join()
                return _state.value.syncStatus ?: withContext(ioDispatcher) { WalletCore.syncStatus(walletId) }
            }
        }

        // Extra guard: if a refresh is currently marked in progress, short-circuit.
        // This protects against accidental double-starts due to UI recomposition or rapid taps.
        if (refreshInProgress.get()) {
            Log.i("WalletManager", "Refresh already in progress (flag); skipping new start walletId=$walletId")
            return _state.value.syncStatus ?: withContext(ioDispatcher) { WalletCore.syncStatus(walletId) }
        }

        refreshCancelRequested.set(false)
        refreshInProgress.set(true)
        val initialTargetHeight = probedDaemonHeight?.takeIf { it > 0L }
        if (initialTargetHeight != null) {
            Log.i(
                "WalletManager",
                "Refresh preflight target height seeded from OkHttp probe: $initialTargetHeight"
            )
        }

        _state.value = _state.value.copy(
            refreshInProgress = true,
            refreshStartedAtMs = System.currentTimeMillis(),
            refreshLastProgressAtMs = null,
            lastError = null,
            refreshTargetHeight = initialTargetHeight,
            gapLimit = gapLimit,
            accountGap = accountGap,
        )

        val job = scope.launch(ioDispatcher) {
            try {
                withContext(ioDispatcher) {
                    // iOS parity: apply scan tuning knobs before starting refresh.
                    // Note: these are process-wide (accountGap via env var) and per-wallet (gapLimit).
                    runCatching {
                        WalletCore.setGapLimit(walletId = walletId, gapLimit = gapLimit)
                    }.onFailure { t ->
                        Log.w("WalletManager", "Failed to apply gapLimit=$gapLimit: ${t.message ?: t.javaClass.simpleName}")
                    }

                    runCatching {
                        WalletCore.setAccountGap(accountGap)
                    }.onFailure { t ->
                        Log.w("WalletManager", "Failed to apply accountGap=$accountGap: ${t.message ?: t.javaClass.simpleName}")
                    }

                    runCatching {
                        WalletCore.setEnv("WALLETCORE_BULK_MODE", ANDROID_BULK_MODE)
                    }.onFailure { t ->
                        Log.w(
                            "WalletManager",
                            "Failed to apply WALLETCORE_BULK_MODE=$ANDROID_BULK_MODE: ${t.message ?: t.javaClass.simpleName}"
                        )
                    }

                    runCatching {
                        WalletCore.setEnv("WALLETCORE_BULK_FETCH_BATCH", ANDROID_BULK_FETCH_BATCH.toString())
                    }.onFailure { t ->
                        Log.w(
                            "WalletManager",
                            "Failed to apply WALLETCORE_BULK_FETCH_BATCH=$ANDROID_BULK_FETCH_BATCH: ${t.message ?: t.javaClass.simpleName}"
                        )
                    }

                    runCatching {
                        WalletCore.setEnv("WALLETCORE_UPSTREAM_BLOCK_BATCH", ANDROID_UPSTREAM_BLOCK_BATCH.toString())
                    }.onFailure { t ->
                        Log.w(
                            "WalletManager",
                            "Failed to apply WALLETCORE_UPSTREAM_BLOCK_BATCH=$ANDROID_UPSTREAM_BLOCK_BATCH: ${t.message ?: t.javaClass.simpleName}"
                        )
                    }

                    runCatching {
                        WalletCore.setEnv("WALLETCORE_SCAN_LOG", "0")
                    }.onFailure { t ->
                        Log.w(
                            "WalletManager",
                            "Failed to apply WALLETCORE_SCAN_LOG=0: ${t.message ?: t.javaClass.simpleName}"
                        )
                    }

                    Log.i(
                        "WalletManager",
                        "Refresh tuning applied: gapLimit=$gapLimit accountGap=$accountGap bulkMode=$ANDROID_BULK_MODE bulkFetchBatch=$ANDROID_BULK_FETCH_BATCH upstreamBlockBatch=$ANDROID_UPSTREAM_BLOCK_BATCH scanLog=0"
                    )

                    // Mirror iOS: start async refresh in the core, then poll `syncStatus` until completion.
                    // This avoids blocking indefinitely inside a single JNI call (sync refresh can hang).
                    Log.i("WalletManager", "ASYNC_REFRESH_PATH_ACTIVE: calling wallet_refresh_async walletId=$walletId nodeUrl=$nodeUrl (gapLimit=$gapLimit accountGap=$accountGap)")
                    WalletCore.refreshAsync(walletId = walletId, nodeUrl = nodeUrl)
                }

                // Snapshot status right after refresh start.
                withContext(ioDispatcher) {
                    runCatching { WalletCore.syncStatus(walletId) }
                }
                    .onSuccess { st ->
                        Log.i(
                            "WalletManager",
                            "Refresh started syncStatus: chainHeight=${st.chainHeight} lastScanned=${st.lastScanned} restoreHeight=${st.restoreHeight} chainTime=${st.chainTime} lastRefreshTimestamp=${st.lastRefreshTimestamp}"
                        )
                    }
                    .onFailure { t ->
                        Log.w("WalletManager", "Refresh started syncStatus failed: ${t.message ?: t.javaClass.simpleName}")
                    }

                // Poll until complete (iOS-style polling + periodic cache persistence + stall detection).
                val st = waitForRefreshCompletion(walletId = walletId)

                // Final export at end of refresh (authoritative).
                withContext(ioDispatcher) {
                    exportCacheAndPersist(walletId)
                }

                _state.value = _state.value.copy(
                    refreshInProgress = false,
                    refreshStartedAtMs = null,
                    refreshLastProgressAtMs = null,
                    syncStatus = st,
                    lastError = null,
                    refreshTargetHeight = null,
                    gapLimit = null,
                    accountGap = null,
                )
                refreshWalletDataSnapshotsNow(walletId)
            } catch (ce: CancellationException) {
                // Kotlin-side cancellation (we still request core cancel in cancelRefresh()).
                val st = withContext(ioDispatcher) {
                    runCatching { WalletCore.syncStatus(walletId) }.getOrNull()
                }
                if (st != null) {
                    _state.value = _state.value.copy(syncStatus = st)
                }
                withContext(ioDispatcher) {
                    exportCacheAndPersist(walletId)
                }
                _state.value = _state.value.copy(
                    refreshInProgress = false,
                    refreshStartedAtMs = null,
                    refreshLastProgressAtMs = null,
                    refreshTargetHeight = null,
                    gapLimit = null,
                    accountGap = null,
                )
            } catch (t: Throwable) {
                // Best-effort: persist progress even on failure.
                withContext(ioDispatcher) {
                    exportCacheAndPersist(walletId)
                }
                val msg = t.message ?: t.javaClass.simpleName
                _state.value = _state.value.copy(
                    refreshInProgress = false,
                    refreshStartedAtMs = null,
                    refreshLastProgressAtMs = null,
                    lastError = msg,
                    refreshTargetHeight = null,
                    gapLimit = null,
                    accountGap = null,
                )
            } finally {
                refreshInProgress.set(false)
            }
        }

        refreshJob = job
        job.join()

        return _state.value.syncStatus ?: withContext(ioDispatcher) { WalletCore.syncStatus(walletId) }
    }

    /**
     * Start a refresh from the manager-owned scope so it survives screen transitions.
     *
     * The wallet setup screen is removed immediately after a successful import/open, which cancels
     * coroutines tied to that composable scope. Using the manager scope preserves iOS-like
     * "import then sync immediately" behavior on a fresh install.
     */
    fun refreshWalletInBackground(): Job {
        return scope.launch(ioDispatcher) {
            // Let Compose finish the wallet-screen transition before native refresh starts.
            // On emulators, starting a long scan during first-frame/JIT work can trigger false ANRs.
            delay(1_500L)
            runCatching {
                refreshWallet()
            }.onFailure { t ->
                _state.value = _state.value.copy(lastError = t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * Force a clean rescan from a specific height.
     *
     * Mirrors the iOS behavior:
     * - reset the core scan cursor
     * - drop the persisted cache so stale state is not restored next launch
     * - persist the new restore height to metadata
     * - start a fresh refresh cycle
     */
    suspend fun rescanFromHeight(fromHeight: Long): WalletCore.SyncStatus {
        require(fromHeight >= 0L) { "fromHeight must be >= 0" }

        val walletId = _state.value.walletId ?: throw IllegalStateException("No wallet open")
        val currentStatus = _state.value.syncStatus

        withContext(ioDispatcher) {
            WalletCore.forceRescanFromHeight(walletId, fromHeight)

            val cache = cacheFile(walletId, mainnet = true)
            if (cache.exists()) {
                runCatching { cache.delete() }
                    .onFailure { t ->
                        Log.w(
                            "WalletManager",
                            "RESCAN delete cache failed walletId=$walletId file=${cache.absolutePath} err=${t.message ?: t.javaClass.simpleName}"
                        )
                    }
            }

            runCatching {
                val f = metadataFile()
                if (f.exists()) {
                    val meta = readMetadata()
                    persistMetadata(
                        meta.copy(
                            restoreHeight = fromHeight,
                            savedAtMs = System.currentTimeMillis(),
                        )
                    )
                }
            }.onFailure { t ->
                Log.w(
                    "WalletManager",
                    "RESCAN metadata update failed walletId=$walletId height=$fromHeight err=${t.message ?: t.javaClass.simpleName}"
                )
            }
        }

        lastExportCacheHash = null
        lastExportCacheLen = null
        lastExportAtMs = 0L

        _state.value = _state.value.copy(
            syncStatus = currentStatus?.copy(
                chainHeight = maxOf(currentStatus.chainHeight, fromHeight),
                lastScanned = fromHeight,
                restoreHeight = fromHeight,
            ),
            balance = WalletCore.Balance(0L, 0L),
            balanceIsStaleWhileSyncing = false,
            lastError = null,
        )

        return refreshWallet()
    }

    /**
     * Clear the persisted on-disk scan cache for the current wallet/network.
     *
     * This mirrors the iOS maintenance action:
     * - delete the exported cache blob used for fast resume
     * - reset export throttling so the next export is not skipped
     *
     * It intentionally does not mutate the in-memory wallet state.
     */
    suspend fun clearScanCache() {
        val walletId = _state.value.walletId ?: throw IllegalStateException("No wallet open")

        withContext(ioDispatcher) {
            val cache = cacheFile(walletId, mainnet = true)
            if (cache.exists()) {
                cache.delete()
            }
        }

        lastExportCacheHash = null
        lastExportCacheLen = null
        lastExportAtMs = 0L

        _state.value = _state.value.copy(
            cacheInfo = null,
            lastError = null,
        )
    }

    /**
     * Request cancellation of an in-flight refresh.
     *
     * This:
     * - sets an app-side flag
     * - calls walletcore refreshCancel (best-effort)
     * - cancels the polling job so UI returns immediately
     * - exports cache best-effort
     */
    fun cancelRefresh() {
        val walletId = _state.value.walletId ?: return
        if (!refreshInProgress.get()) return

        refreshCancelRequested.set(true)

        scope.launch {
            withContext(ioDispatcher) {
                runCatching {
                    WalletCore.refreshCancel(walletId)
                }.onFailure { t ->
                    // Don't block cancellation on this.
                    _state.value = _state.value.copy(lastError = "refreshCancel failed: ${t.message ?: t.javaClass.simpleName}")
                }
                exportCacheAndPersist(walletId)
            }
        }

        refreshJob?.cancel()

        _state.value = _state.value.copy(
            refreshInProgress = false,
            refreshTargetHeight = null,
            gapLimit = null,
            accountGap = null,
        )
    }

    /**
     * Snapshot state for backgrounding. (Best-effort)
     *
     * This does NOT cancel refresh.
     */
    fun snapshotState() {
        val walletId = _state.value.walletId ?: return
        scope.launch {
            withContext(ioDispatcher) {
                exportCacheAndPersist(walletId)
            }
        }
    }

    /**
     * Best-effort sync status query and store into state.
     */
    fun refreshStatusSnapshot() {
        val walletId = _state.value.walletId ?: return
        scope.launch {
            updateStatusSnapshot(walletId)
        }
    }

    /**
     * Refresh and store balances in state (best-effort).
     *
     * This does NOT require a refresh; it reads whatever the core currently knows.
     */
    fun refreshBalanceSnapshot() {
        val walletId = _state.value.walletId ?: return
        scope.launch {
            refreshBalanceSnapshotNow(walletId)
        }
    }

    private suspend fun refreshBalanceSnapshotNow(walletId: String) {
        val bal = withContext(ioDispatcher) {
            runCatching { WalletCore.getBalance(walletId) }.getOrNull()
        }
        if (bal != null) {
            applyBalanceSnapshot(
                balance = bal,
                allowAuthoritativeZero = isZeroBalanceAuthoritative(_state.value)
            )
        }
    }

    /**
     * Refresh and store transfers JSON in state (best-effort).
     *
     * This returns raw JSON for now; parsing/rendering can be layered on in UI.
     */
    fun refreshTransfersSnapshot() {
        val walletId = _state.value.walletId ?: return
        scope.launch {
            refreshTransfersSnapshotNow(walletId)
        }
    }

    private suspend fun refreshTransfersSnapshotNow(walletId: String) {
        val json = withContext(ioDispatcher) {
            runCatching { WalletCore.listTransfersJson(walletId) }.getOrNull()
        }
        if (json != null) {
            val (parsed, parseErr) = parseTransfersJson(json)
            _state.value = _state.value.copy(
                transfersJson = json,
                transfers = parsed,
                transfersParseError = parseErr,
                lastTransfersRefreshAtMs = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Convenience helper for Wallet screen to update both balance + transfers.
     */
    fun refreshWalletDataSnapshots() {
        val walletId = _state.value.walletId ?: return
        scope.launch {
            refreshWalletDataSnapshotsNow(walletId)
        }
    }

    private suspend fun refreshWalletDataSnapshotsNow(walletId: String) {
        refreshBalanceSnapshotNow(walletId)
        refreshTransfersSnapshotNow(walletId)
    }

    private fun isZeroBalanceAuthoritative(state: UiState): Boolean {
        val st = state.syncStatus ?: return false
        val observedNetworkTip = st.chainHeight > st.restoreHeight || st.chainTime > 0
        val syncedWithinTolerance = st.chainHeight > 0 && st.lastScanned + 3 >= st.chainHeight
        return observedNetworkTip && syncedWithinTolerance && !state.refreshInProgress
    }

    private fun applyBalanceSnapshot(
        balance: WalletCore.Balance,
        allowAuthoritativeZero: Boolean,
    ) {
        val current = _state.value
        val knownTotal = maxOf(current.balance?.totalPiconero ?: 0L, 0L)
        val knownUnlocked = maxOf(current.balance?.unlockedPiconero ?: 0L, 0L)
        val hasKnownNonZero = knownTotal > 0L || knownUnlocked > 0L
        val proposedZero = balance.totalPiconero == 0L && balance.unlockedPiconero == 0L

        if (proposedZero && hasKnownNonZero && !allowAuthoritativeZero) {
            Log.i(
                "WalletManager",
                "BALANCE_GUARD preserve-known-nonzero knownTotal=$knownTotal proposedTotal=0 refreshInProgress=${current.refreshInProgress}"
            )
            _state.value = current.copy(
                balanceIsStaleWhileSyncing = true,
                lastBalanceRefreshAtMs = System.currentTimeMillis(),
            )
            return
        }

        _state.value = current.copy(
            balance = balance,
            balanceIsStaleWhileSyncing = false,
            lastBalanceRefreshAtMs = System.currentTimeMillis(),
        )
    }

    /**
     * Preview fee for one or more destinations.
     *
     * This mirrors the iOS "preview fee" flow and is safe to call frequently from UI.
     */
    suspend fun previewFee(
        destinations: List<SendJson.Destination>,
        ringLen: Int = 16,
    ): SendJson.FeeResult {
        val walletId = _state.value.walletId ?: throw IllegalStateException("No wallet open")
        val nodeUrl = resolveBroadcastNodeUrl()

        return withContext(ioDispatcher) {
            applyProxyEnv(forBroadcast = true)
            val destinationsJson = SendJson.encodeDestinations(destinations)
            val raw = withOptionalSiblingFeeRetry(nodeUrl) { endpoint ->
                WalletCore.previewFeeJson(
                    walletId = walletId,
                    destinationsJson = destinationsJson,
                    ringLen = ringLen,
                    nodeUrl = endpoint,
                )
            }
            val res = SendJson.decodeFeeResult(raw)

            _state.value = _state.value.copy(
                lastFeePreview = res,
                lastError = null,
                lastSendOrSweepAtMs = System.currentTimeMillis(),
            )
            res
        }
    }

    suspend fun getBalance(fromSubaddressMinor: Int): WalletCore.Balance {
        require(fromSubaddressMinor >= 0) { "fromSubaddressMinor must be >= 0" }
        val walletId = _state.value.walletId ?: throw IllegalStateException("No wallet open")

        return withContext(ioDispatcher) {
            WalletCore.getBalanceWithFilter(
                walletId = walletId,
                filterJson = filterJsonForSubaddressMinor(fromSubaddressMinor),
            )
        }
    }

    suspend fun previewFee(
        fromSubaddressMinor: Int,
        destinations: List<SendJson.Destination>,
        ringLen: Int = 16,
    ): SendJson.FeeResult {
        require(fromSubaddressMinor >= 0) { "fromSubaddressMinor must be >= 0" }
        val walletId = _state.value.walletId ?: throw IllegalStateException("No wallet open")
        val nodeUrl = resolveBroadcastNodeUrl()

        return withContext(ioDispatcher) {
            applyProxyEnv(forBroadcast = true)
            val destinationsJson = SendJson.encodeDestinations(destinations)
            val raw = withOptionalSiblingFeeRetry(nodeUrl) { endpoint ->
                WalletCore.previewFeeJsonWithFilter(
                    walletId = walletId,
                    destinationsJson = destinationsJson,
                    filterJson = filterJsonForSubaddressMinor(fromSubaddressMinor),
                    ringLen = ringLen,
                    nodeUrl = endpoint,
                )
            }
            val res = SendJson.decodeFeeResult(raw)

            _state.value = _state.value.copy(
                lastFeePreview = res,
                lastError = null,
                lastSendOrSweepAtMs = System.currentTimeMillis(),
            )
            res
        }
    }

    /**
     * Send a transaction (single destination).
     *
     * Important: mirrors iOS behavior by persisting cache immediately after send so the pending
     * outgoing survives process death / restart.
     */
    suspend fun send(
        toAddress: String,
        amountPiconero: Long,
        ringLen: Int = 16,
    ): SendJson.SendResult = withSendLock {
        val walletId = _state.value.walletId ?: throw IllegalStateException("No wallet open")
        val nodeUrl = resolveBroadcastNodeUrl()

        val raw = withContext(ioDispatcher) {
            applyProxyEnv(forBroadcast = true)
            withOptionalSiblingFeeRetry(nodeUrl) { endpoint ->
                WalletCore.sendJson(
                    walletId = walletId,
                    toAddress = toAddress,
                    amountPiconero = amountPiconero,
                    ringLen = ringLen,
                    nodeUrl = endpoint,
                )
            }
        }
        val res = SendJson.decodeSendResult(raw)

        // Persist immediately so pending outgoing survives restart before next refresh.
        withContext(ioDispatcher) {
            exportCacheAndPersist(walletId)
        }

        _state.value = _state.value.copy(
            lastSendResult = res,
            lastError = null,
            lastSendOrSweepAtMs = System.currentTimeMillis(),
        )

        // Best-effort: refresh visible data snapshots (balance/transfers) after send.
        refreshWalletDataSnapshotsNow(walletId)

        res
    }

    suspend fun send(
        fromSubaddressMinor: Int,
        toAddress: String,
        amountPiconero: Long,
        ringLen: Int = 16,
    ): SendJson.SendResult = withSendLock {
        require(fromSubaddressMinor >= 0) { "fromSubaddressMinor must be >= 0" }
        val walletId = _state.value.walletId ?: throw IllegalStateException("No wallet open")
        val nodeUrl = resolveBroadcastNodeUrl()

        val raw = withContext(ioDispatcher) {
            applyProxyEnv(forBroadcast = true)
            withOptionalSiblingFeeRetry(nodeUrl) { endpoint ->
                WalletCore.sendJsonWithFilter(
                    walletId = walletId,
                    destinationsJson = SendJson.encodeDestinations(
                        listOf(
                            SendJson.Destination(
                                address = toAddress,
                                amount = amountPiconero,
                            )
                        )
                    ),
                    filterJson = filterJsonForSubaddressMinor(fromSubaddressMinor),
                    ringLen = ringLen,
                    nodeUrl = endpoint,
                )
            }
        }
        val res = SendJson.decodeSendResult(raw)

        withContext(ioDispatcher) {
            exportCacheAndPersist(walletId)
        }

        _state.value = _state.value.copy(
            lastSendResult = res,
            lastError = null,
            lastSendOrSweepAtMs = System.currentTimeMillis(),
        )

        refreshWalletDataSnapshotsNow(walletId)

        res
    }

    /**
     * Preview "send max" (sweep).
     */
    suspend fun previewSweep(
        toAddress: String,
        ringLen: Int = 16,
    ): SendJson.SweepPreviewResult {
        val walletId = _state.value.walletId ?: throw IllegalStateException("No wallet open")
        val nodeUrl = resolveBroadcastNodeUrl()

        return withContext(ioDispatcher) {
            applyProxyEnv(forBroadcast = true)
            val raw = withOptionalSiblingFeeRetry(nodeUrl) { endpoint ->
                WalletCore.previewSweepJson(
                    walletId = walletId,
                    toAddress = toAddress,
                    ringLen = ringLen,
                    nodeUrl = endpoint,
                )
            }
            val res = SendJson.decodeSweepPreviewResult(raw)

            _state.value = _state.value.copy(
                lastSweepPreview = res,
                lastError = null,
                lastSendOrSweepAtMs = System.currentTimeMillis(),
            )
            res
        }
    }

    suspend fun previewSweep(
        fromSubaddressMinor: Int,
        toAddress: String,
        ringLen: Int = 16,
    ): SendJson.SweepPreviewResult {
        require(fromSubaddressMinor >= 0) { "fromSubaddressMinor must be >= 0" }
        val walletId = _state.value.walletId ?: throw IllegalStateException("No wallet open")
        val nodeUrl = resolveBroadcastNodeUrl()

        return withContext(ioDispatcher) {
            applyProxyEnv(forBroadcast = true)
            val raw = withOptionalSiblingFeeRetry(nodeUrl) { endpoint ->
                WalletCore.previewSweepJsonWithFilter(
                    walletId = walletId,
                    toAddress = toAddress,
                    filterJson = filterJsonForSubaddressMinor(fromSubaddressMinor),
                    ringLen = ringLen,
                    nodeUrl = endpoint,
                )
            }
            val res = SendJson.decodeSweepPreviewResult(raw)

            _state.value = _state.value.copy(
                lastSweepPreview = res,
                lastError = null,
                lastSendOrSweepAtMs = System.currentTimeMillis(),
            )
            res
        }
    }

    /**
     * Sweep ("send max") to a destination.
     *
     * Important: mirrors iOS behavior by persisting cache immediately after sweep so the pending
     * outgoing survives process death / restart.
     */
    suspend fun sweep(
        toAddress: String,
        ringLen: Int = 16,
    ): SendJson.SweepSendResult = withSendLock {
        val walletId = _state.value.walletId ?: throw IllegalStateException("No wallet open")
        val nodeUrl = resolveBroadcastNodeUrl()

        val raw = withContext(ioDispatcher) {
            applyProxyEnv(forBroadcast = true)
            withOptionalSiblingFeeRetry(nodeUrl) { endpoint ->
                WalletCore.sweepJson(
                    walletId = walletId,
                    toAddress = toAddress,
                    ringLen = ringLen,
                    nodeUrl = endpoint,
                )
            }
        }
        val res = SendJson.decodeSweepSendResult(raw)

        // Persist immediately so pending outgoing survives restart before next refresh.
        withContext(ioDispatcher) {
            exportCacheAndPersist(walletId)
        }

        _state.value = _state.value.copy(
            lastSweepSendResult = res,
            lastError = null,
            lastSendOrSweepAtMs = System.currentTimeMillis(),
        )

        // Best-effort: refresh visible data snapshots (balance/transfers) after sweep.
        refreshWalletDataSnapshotsNow(walletId)

        res
    }

    suspend fun sweep(
        fromSubaddressMinor: Int,
        toAddress: String,
        ringLen: Int = 16,
    ): SendJson.SweepSendResult = withSendLock {
        require(fromSubaddressMinor >= 0) { "fromSubaddressMinor must be >= 0" }
        val walletId = _state.value.walletId ?: throw IllegalStateException("No wallet open")
        val nodeUrl = resolveBroadcastNodeUrl()

        val raw = withContext(ioDispatcher) {
            applyProxyEnv(forBroadcast = true)
            withOptionalSiblingFeeRetry(nodeUrl) { endpoint ->
                WalletCore.sweepJsonWithFilter(
                    walletId = walletId,
                    toAddress = toAddress,
                    filterJson = filterJsonForSubaddressMinor(fromSubaddressMinor),
                    ringLen = ringLen,
                    nodeUrl = endpoint,
                )
            }
        }
        val res = SendJson.decodeSweepSendResult(raw)

        withContext(ioDispatcher) {
            exportCacheAndPersist(walletId)
        }

        _state.value = _state.value.copy(
            lastSweepSendResult = res,
            lastError = null,
            lastSendOrSweepAtMs = System.currentTimeMillis(),
        )

        refreshWalletDataSnapshotsNow(walletId)

        res
    }

    private fun parseTransfersJson(json: String): Pair<List<Transfer>, String?> {
        return try {
            val parsed = transfersJsonParser.decodeFromString<List<Transfer>>(json)
            parsed to null
        } catch (t: Throwable) {
            emptyList<Transfer>() to (t.message ?: t.javaClass.simpleName)
        }
    }

    private fun isTerminalRefreshCoreError(message: String): Boolean {
        val normalized = message.lowercase()
        return normalized.contains("contiguous_scannable_blocks timeout/disconnect") ||
            normalized.contains("invalid node") ||
            normalized.contains("failed to connect daemon") ||
            normalized.contains("response wasn't the expected json")
    }

    private suspend fun updateStatusSnapshot(walletId: String) {
        val st = withContext(ioDispatcher) {
            runCatching { WalletCore.syncStatus(walletId) }.getOrNull()
        }
        if (st != null) {
            _state.value = _state.value.copy(syncStatus = st)
        }
    }

    /**
     * Best-effort daemon height probe using OkHttp against the configured node URL.
     *
     * This answers: "what does the emulator network stack see for this node?"
     *
     * Expects the Monero daemon RPC endpoint `/get_height`, which returns JSON like:
     *   { "height": 3601010, ... }
     */
    private fun probeDaemonHeightViaOkHttp(nodeUrl: String): Long? {
        val base = nodeUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null

        val url = "$base/get_height"

        val client = buildDaemonHttpClient(forBroadcast = false)

        val req = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null

            // Minimal parse to avoid adding a JSON dependency here.
            // Look for:  "height": 3601010
            val m = Regex("\"height\"\\s*:\\s*(\\d+)").find(body) ?: return null
            return m.groupValues.getOrNull(1)?.toLongOrNull()
        }
    }

    /**
     * Poll the core until refresh completes, closely mirroring iOS behavior:
     * - take a fixed chain target snapshot (chainHeight once it exceeds restoreHeight)
     * - consider refresh complete when lastScanned >= effectiveTarget
     * - periodically persist cache while refresh is running
     */
    private suspend fun waitForRefreshCompletion(
        walletId: String,
        pollIntervalMs: Long = 1_000L,
        slowFetchWarningMs: Long = 45_000L,
        hardStallTimeoutMs: Long = 180_000L,
    ): WalletCore.SyncStatus = withContext(ioDispatcher) {
        var targetHeight: Long? = null
        var lastScannedSnapshot: Long = 0
        val refreshStartMs = System.currentTimeMillis()
        var lastProgressAtMs = refreshStartMs
        var slowFetchWarningLogged = false

        // Periodic persistence while refresh is running.
        var lastPersistAtMs = 0L
        val persistIntervalMs = 30_000L

        // Mirror iOS: sample core error state periodically even if progress is happening.
        var lastCoreErrSampleAtMs = 0L
        val coreErrSampleIntervalMs = 10_000L

        // Mirror iOS: log progress periodically and push syncStatus into state at a UI-safe cadence.
        // Updating Compose state every 200ms during a 100k+ block refresh can trip emulator ANRs.
        var lastProgressLogAtMs = 0L
        val progressLogIntervalMs = 30_000L
        var lastUiStatusAtMs = 0L
        val uiStatusIntervalMs = 5_000L
        var lastRateSampleAtMs = System.currentTimeMillis()
        var lastRateSampleScanned = 0L

        while (isActive) {
            ensureActive()

            if (refreshCancelRequested.get()) {
                exportCacheAndPersist(walletId)
                throw CancellationException("refresh cancelled")
            }

            val st = WalletCore.syncStatus(walletId)

            // Mirror iOS: sample core error state even if progress continues.
            val nowMs = System.currentTimeMillis()

            // Capture the initial target chain height once (so we don't chase a moving tip).
            // Avoid locking onto restoreHeight as the target (which reads as chainHeight initially).
            if (targetHeight == null && st.chainHeight > st.restoreHeight) {
                targetHeight = st.chainHeight
                _state.value = _state.value.copy(
                    syncStatus = st,
                    refreshTargetHeight = targetHeight,
                    refreshLastProgressAtMs = null,
                )
                lastUiStatusAtMs = nowMs
                Log.i("WalletManager", "Refresh target height set to $targetHeight (restoreHeight=${st.restoreHeight})")

                // Reset rate sampling baseline to avoid an initial "rate spike" on the first progress log.
                lastRateSampleAtMs = nowMs
                lastRateSampleScanned = st.lastScanned
            }
            if (nowMs - lastCoreErrSampleAtMs >= coreErrSampleIntervalMs) {
                lastCoreErrSampleAtMs = nowMs
                val coreErr = runCatching { WalletCore.lastErrorMessage() }.getOrNull()
                if (!coreErr.isNullOrBlank()) {
                    Log.w("WalletManager", "Core error sample during refresh: $coreErr")
                    if (isTerminalRefreshCoreError(coreErr)) {
                        exportCacheAndPersist(walletId)
                        throw IOException(coreErr)
                    }
                }
            }

            // Mirror iOS: periodic progress logging (blocks remaining + rough blocks/sec).
            if (nowMs - lastProgressLogAtMs >= progressLogIntervalMs) {
                lastProgressLogAtMs = nowMs

                val tip = targetHeight ?: st.chainHeight
                val remaining = if (tip > 0) maxOf(0L, tip - st.lastScanned) else -1L

                val localBlocksPerSec = run {
                    val dtMs = maxOf(1L, nowMs - lastRateSampleAtMs)
                    val dScanned = (st.lastScanned - lastRateSampleScanned).coerceAtLeast(0L)
                    (dScanned.toDouble() * 1000.0) / dtMs.toDouble()
                }
                val averageBlocksPerSec = run {
                    val elapsedMs = maxOf(1L, nowMs - refreshStartMs)
                    val scannedSinceStart = (st.lastScanned - st.restoreHeight).coerceAtLeast(0L)
                    (scannedSinceStart.toDouble() * 1000.0) / elapsedMs.toDouble()
                }
                val phase = when {
                    targetHeight == null -> "connecting"
                    st.lastScanned <= st.restoreHeight -> "fetching-first-batch"
                    localBlocksPerSec <= 0.0 -> "fetching-next-batch"
                    else -> "scanning"
                }

                Log.i(
                    "WalletManager",
                    "⏳ Refresh progress: scanned=${st.lastScanned}, restore=${st.restoreHeight}, chain=${st.chainHeight}, target=${targetHeight ?: 0L}, remaining=$remaining, phase=$phase, intervalRate=${"%.1f".format(localBlocksPerSec)} blks/s avgRate=${"%.1f".format(averageBlocksPerSec)} blks/s"
                )

                lastRateSampleAtMs = nowMs
                lastRateSampleScanned = st.lastScanned
            }

            // Track progress.
            // Consider "progress" as either:
            // - lastScanned advancing, OR
            // - targetHeight becoming known (transition from unknown -> known).
            if (st.lastScanned > lastScannedSnapshot) {
                lastScannedSnapshot = st.lastScanned
                lastProgressAtMs = nowMs
                slowFetchWarningLogged = false
            } else if (targetHeight != null && lastScannedSnapshot == 0L) {
                // Defensive: if we started with lastScanned=0 and target gets set, reset the timer so we
                // don't report a stall before any blocks are scanned.
                lastProgressAtMs = nowMs
            }

            if (nowMs - lastUiStatusAtMs >= uiStatusIntervalMs) {
                lastUiStatusAtMs = nowMs
                _state.value = _state.value.copy(
                    syncStatus = st,
                    refreshLastProgressAtMs = if (lastScannedSnapshot > 0L) lastProgressAtMs else null,
                )
            }

            // Periodic cache persistence.
            if (st.lastScanned > 0 && nowMs - lastPersistAtMs >= persistIntervalMs) {
                exportCacheAndPersist(walletId)
                lastPersistAtMs = nowMs
            }

            // Completion condition.
            // Only evaluate completion once we have captured a stable target height.
            targetHeight?.let { target ->
                val effectiveTarget = maxOf(target, st.restoreHeight)
                if (effectiveTarget > 0 && st.lastScanned >= effectiveTarget) {
                    return@withContext st
                }
            }

            // Stall detection:
            // Only trigger once:
            // - we have a target height (so we know refresh actually started), AND
            // - there is remaining work to do (avoid false stalls at completion / when target is unknown).
            val tip = targetHeight
            val remaining: Long? = tip?.let { t ->
                if (t > 0) maxOf(0L, t - st.lastScanned) else null
            }

            val stalledForMs = nowMs - lastProgressAtMs
            if (tip != null && remaining != null && remaining > 0 && stalledForMs > slowFetchWarningMs && !slowFetchWarningLogged) {
                slowFetchWarningLogged = true
                Log.w(
                    "WalletManager",
                    "SLOW_NODE_FETCH: no scan cursor advance for ${stalledForMs}ms walletId=$walletId " +
                        "lastScanned=${st.lastScanned} chainHeight=${st.chainHeight} target=$tip remaining=$remaining"
                )
            }

            if (tip != null && remaining != null && remaining > 0 && stalledForMs > hardStallTimeoutMs) {
                val coreErr = runCatching { WalletCore.lastErrorMessage() }.getOrNull()

                Log.e(
                    "WalletManager",
                    "STALL: refresh appears stuck (>${hardStallTimeoutMs}ms) walletId=$walletId " +
                        "lastScanned=${st.lastScanned} restoreHeight=${st.restoreHeight} chainHeight=${st.chainHeight} " +
                        "target=$tip remaining=$remaining lastError=${coreErr ?: "<null>"}"
                )

                if (!coreErr.isNullOrBlank()) {
                    Log.w("WalletManager", "Core lastErrorMessage (stall): $coreErr")
                }

                exportCacheAndPersist(walletId)
                throw IOException(
                    "Refresh stalled (>${hardStallTimeoutMs}ms) lastScanned=${st.lastScanned} chainHeight=${st.chainHeight}" +
                        (if (!coreErr.isNullOrBlank()) " coreErr=$coreErr" else "")
                )
            }

            delay(pollIntervalMs.coerceAtLeast(50L))
        }

        throw CancellationException("refresh polling cancelled")
    }

    /**
     * Cache path:
     *   app files dir / WalletCaches / mainnet / <walletId>.cache
     */
    private fun cacheFile(walletId: String, mainnet: Boolean = true): File {
        val netDir = if (mainnet) "mainnet" else "stagenet"
        val dir = File(appContext.filesDir, "WalletCaches/$netDir")
        return File(dir, "$walletId.cache")
    }

    private fun ensureCacheDirExists(file: File) {
        val dir = file.parentFile ?: return
        if (!dir.exists()) dir.mkdirs()
    }

    companion object {
        const val DEFAULT_WALLET_ID: String = "main_wallet"

        // Android emulator pays a high fixed cost per host-bridge RPC. Larger batches keep the
        // UI responsive while reducing round trips; walletcore clamps this env to a safe range.
        private const val ANDROID_BULK_FETCH_BATCH = 500
        private const val ANDROID_UPSTREAM_BLOCK_BATCH = 500

        // Android experiment: prefer the range-style binary endpoint while Cuprate wallet2
        // parity is still being finished.
        private const val ANDROID_BULK_MODE = "range"
    }

    private fun importCacheIfPresent(walletId: String) {
        val f = cacheFile(walletId, mainnet = true)

        val stBefore = runCatching { WalletCore.syncStatus(walletId) }.getOrNull()
        Log.i(
            "WalletManager",
            "CACHE_IMPORT pre walletId=$walletId file=${f.absolutePath} exists=${f.exists()} bytesOnDisk=${if (f.exists()) f.length() else 0} lastModified=${if (f.exists()) f.lastModified() else 0} " +
                "syncStatusBefore(chainHeight=${stBefore?.chainHeight} lastScanned=${stBefore?.lastScanned} restoreHeight=${stBefore?.restoreHeight})"
        )

        if (!f.exists()) return

        val bytes = runCatching { f.readBytes() }.getOrNull()
        if (bytes == null) {
            Log.w("WalletManager", "CACHE_IMPORT failed to read bytes walletId=$walletId file=${f.absolutePath}")
            return
        }
        if (bytes.isEmpty()) {
            Log.w("WalletManager", "CACHE_IMPORT empty cache file walletId=$walletId file=${f.absolutePath}")
            return
        }

        runCatching {
            WalletCore.importCache(walletId, bytes)
        }.onFailure { t ->
            // Best-effort only: don't fail opening wallet due to bad cache.
            val msg = "cache import failed: ${t.message ?: t.javaClass.simpleName}"
            Log.w("WalletManager", "CACHE_IMPORT error walletId=$walletId bytes=${bytes.size} err=$msg")
            _state.value = _state.value.copy(lastError = msg)
            return
        }

        val stAfter = runCatching { WalletCore.syncStatus(walletId) }.getOrNull()
        Log.i(
            "WalletManager",
            "CACHE_IMPORT ok walletId=$walletId importedBytes=${bytes.size} " +
                "syncStatusAfter(chainHeight=${stAfter?.chainHeight} lastScanned=${stAfter?.lastScanned} restoreHeight=${stAfter?.restoreHeight})"
        )
    }

    private fun exportCacheAndPersist(walletId: String) {
        val stBefore = runCatching { WalletCore.syncStatus(walletId) }.getOrNull()
        val cache = runCatching { WalletCore.exportCache(walletId) }.getOrNull()
        if (cache == null) {
            Log.w(
                "WalletManager",
                "CACHE_EXPORT skipped walletId=$walletId (exportCache returned null) " +
                    "syncStatus(chainHeight=${stBefore?.chainHeight} lastScanned=${stBefore?.lastScanned} restoreHeight=${stBefore?.restoreHeight})"
            )
            return
        }

        // Throttle redundant exports:
        // - If we exported the same bytes recently, skip writing to disk.
        // This reduces churn during refresh polling and at app startup where multiple calls can happen.
        val nowMs = System.currentTimeMillis()
        val hash = cache.contentHashCode()
        val len = cache.size
        val isSameAsLast = (lastExportCacheHash == hash && lastExportCacheLen == len)
        val throttleWindowMs = 3_000L

        if (isSameAsLast && (nowMs - lastExportAtMs) < throttleWindowMs) {
            Log.i(
                "WalletManager",
                "CACHE_EXPORT throttled walletId=$walletId bytes=$len (unchanged within ${throttleWindowMs}ms) " +
                    "syncStatus(chainHeight=${stBefore?.chainHeight} lastScanned=${stBefore?.lastScanned} restoreHeight=${stBefore?.restoreHeight})"
            )
            return
        }

        val f = cacheFile(walletId, mainnet = true)
        runCatching {
            ensureCacheDirExists(f)
            f.writeBytes(cache)

            lastExportCacheHash = hash
            lastExportCacheLen = len
            lastExportAtMs = nowMs

            _state.value = _state.value.copy(
                cacheInfo = CacheInfo(
                    filePath = f.absolutePath,
                    bytesOnDisk = f.length(),
                    lastSavedAtMs = nowMs,
                )
            )

            Log.i(
                "WalletManager",
                "CACHE_EXPORT ok walletId=$walletId wroteBytes=${cache.size} file=${f.absolutePath} bytesOnDisk=${f.length()} " +
                    "syncStatus(chainHeight=${stBefore?.chainHeight} lastScanned=${stBefore?.lastScanned} restoreHeight=${stBefore?.restoreHeight})"
            )
        }.onFailure { t ->
            val msg = "cache export failed: ${t.message ?: t.javaClass.simpleName}"
            Log.w("WalletManager", "CACHE_EXPORT error walletId=$walletId file=${f.absolutePath} bytes=${cache.size} err=$msg")
            _state.value = _state.value.copy(lastError = msg)
        }
    }

    /**
     * Load the stored wallet metadata (if present), open the wallet core, import cache, and refresh.
     *
     * Mirrors iOS `loadStoredWalletOnLaunch()` behavior at a high level.
     *
     * Returns true if a wallet was loaded and opened, false if none exists.
     */
    suspend fun loadStoredWalletOnLaunch(): Boolean = withContext(ioDispatcher) {
        val f = metadataFile()
        if (!f.exists()) {
            _state.value = _state.value.copy(hasStoredWallet = false)
            return@withContext false
        }

        val meta = runCatching { readMetadata() }.getOrElse { t ->
            _state.value = _state.value.copy(
                hasStoredWallet = true,
                lastError = "Failed to read stored wallet metadata: ${t.message ?: t.javaClass.simpleName}"
            )
            return@withContext false
        }

        Log.i(
            "WalletManager",
            "LOAD_STORED_WALLET start walletId=${meta.walletId} restoreHeight=${meta.restoreHeight} mainnet=${meta.mainnet} nodeUrl=${meta.nodeUrl}"
        )

        runCatching {
            WalletCore.openFromMnemonic(
                walletId = meta.walletId,
                mnemonic = meta.mnemonic,
                restoreHeight = meta.restoreHeight,
                mainnet = meta.mainnet
            )
        }.onFailure { t ->
            _state.value = _state.value.copy(
                hasStoredWallet = true,
                lastError = "Failed to open stored wallet: ${t.message ?: t.javaClass.simpleName}"
            )
            return@withContext false
        }

        val stAfterOpen = runCatching { WalletCore.syncStatus(meta.walletId) }.getOrNull()
        Log.i(
            "WalletManager",
            "LOAD_STORED_WALLET opened walletId=${meta.walletId} syncStatusAfterOpen(chainHeight=${stAfterOpen?.chainHeight} lastScanned=${stAfterOpen?.lastScanned} restoreHeight=${stAfterOpen?.restoreHeight})"
        )

        // Derive and cache primary address for UI.
        // If derivation fails, log and surface it (non-fatal) instead of silently producing null.
        val derivedAddress: String? = runCatching {
            WalletCore.derivePrimaryAddressFromMnemonic(
                mnemonic = meta.mnemonic,
                mainnet = meta.mainnet
            )
        }.onFailure { t ->
            Log.w(
                "WalletManager",
                "derivePrimaryAddressFromMnemonic (loadStoredWalletOnLaunch) failed: ${t.message ?: t.toString()}"
            )
            Log.w("WalletManager", "derivePrimaryAddressFromMnemonic (loadStoredWalletOnLaunch) throwable=$t")
            _state.value = _state.value.copy(
                lastError = "Failed to derive primary address: ${t.message ?: t.toString()}"
            )
        }.getOrNull()

        _state.value = _state.value.copy(
            walletId = meta.walletId,
            nodeUrl = meta.nodeUrl,
            walletAddress = derivedAddress,
            hasStoredWallet = true,
            lastLoadedFromDiskAtMs = System.currentTimeMillis(),
            lastError = null,
        )

        // Best-effort retry if we ended up with no address in state.
        if (derivedAddress.isNullOrBlank()) {
            deriveAndStorePrimaryAddressBestEffort(
                mnemonic = meta.mnemonic,
                mainnet = meta.mainnet
            )
        }

        // Import cache best-effort, then snapshot status.
        importCacheIfPresent(meta.walletId)
        updateStatusSnapshot(meta.walletId)
        refreshWalletDataSnapshotsNow(meta.walletId)

        val stAfterImport = runCatching { WalletCore.syncStatus(meta.walletId) }.getOrNull()
        Log.i(
            "WalletManager",
            "LOAD_STORED_WALLET afterImport walletId=${meta.walletId} syncStatusAfterImport(chainHeight=${stAfterImport?.chainHeight} lastScanned=${stAfterImport?.lastScanned} restoreHeight=${stAfterImport?.restoreHeight})"
        )

        true
    }

    /**
     * Remove the single-wallet slot from disk (metadata + cache file).
     *
     * This mirrors iOS "Replace existing wallet?" destructive flow.
     */
    suspend fun clearStoredWallet() = withContext(ioDispatcher) {
        runCatching {
            val m = metadataFile()
            if (m.exists()) m.delete()
        }
        runCatching {
            val c = cacheFile(DEFAULT_WALLET_ID, mainnet = true)
            if (c.exists()) c.delete()
        }
        runCatching {
            val r = receiveSubaddressesFile()
            if (r.exists()) r.delete()
        }

        _state.value = _state.value.copy(
            walletId = null,
            syncStatus = null,
            balance = null,
            transfersJson = null,
            transfers = emptyList(),
            transfersParseError = null,
            cacheInfo = null,
            hasStoredWallet = false,
            lastPersistedAtMs = System.currentTimeMillis(),
        )
    }

    private fun persistMetadata(meta: StoredWalletMetadata) {
        // Keep this synchronous and best-effort (caller is already in a safe context).
        runCatching {
            val f = metadataFile()
            ensureParentDirExists(f)
            val encrypted = MnemonicCipher.encrypt(meta.mnemonic)
            val json = JSONObject()
                .put("walletId", meta.walletId)
                .put("encryptedMnemonic", encrypted.ciphertextBase64)
                .put("mnemonicIv", encrypted.ivBase64)
                .put("restoreHeight", meta.restoreHeight)
                .put("mainnet", meta.mainnet)
                .put("nodeUrl", meta.nodeUrl)
                .put("savedAtMs", meta.savedAtMs)
                .put("formatVersion", 2)
            f.writeText(json.toString(2))
            _state.value = _state.value.copy(lastPersistedAtMs = System.currentTimeMillis(), hasStoredWallet = true)
        }.onFailure { t ->
            _state.value = _state.value.copy(lastError = "Failed to persist metadata: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun readMetadata(): StoredWalletMetadata {
        val f = metadataFile()
        val json = JSONObject(f.readText())

        val walletId = json.optString("walletId", DEFAULT_WALLET_ID).ifBlank { DEFAULT_WALLET_ID }
        val restoreHeight = if (json.has("restoreHeight")) json.optLong("restoreHeight", 0L) else 0L
        val mainnet = if (json.has("mainnet")) json.optBoolean("mainnet", true) else true
        val nodeUrl = migrateLegacyDefaultNodeUrl(
            json.optString("nodeUrl", defaultNodeUrl()).ifBlank { defaultNodeUrl() }
        )
        val savedAtMs = if (json.has("savedAtMs")) json.optLong("savedAtMs", 0L) else 0L

        val encryptedMnemonic = json.optString("encryptedMnemonic", "").trim()
        val mnemonicIv = json.optString("mnemonicIv", "").trim()

        val mnemonic = if (encryptedMnemonic.isNotEmpty() && mnemonicIv.isNotEmpty()) {
            MnemonicCipher.decrypt(
                ivBase64 = mnemonicIv,
                ciphertextBase64 = encryptedMnemonic
            )
        } else {
            val plaintextMnemonic = json.optString("mnemonic", "").trim()
            if (plaintextMnemonic.isEmpty()) {
                throw IllegalStateException("Stored metadata does not contain a mnemonic")
            }

            // Transparent migration from legacy plaintext metadata.
            persistMetadata(
                StoredWalletMetadata(
                    walletId = walletId,
                    mnemonic = plaintextMnemonic,
                    restoreHeight = restoreHeight,
                    mainnet = mainnet,
                    nodeUrl = nodeUrl,
                    savedAtMs = savedAtMs,
                )
            )
            plaintextMnemonic
        }

        return StoredWalletMetadata(
            walletId = walletId,
            mnemonic = mnemonic,
            restoreHeight = restoreHeight,
            mainnet = mainnet,
            nodeUrl = nodeUrl,
            savedAtMs = savedAtMs,
        )
    }

    private fun metadataFile(): File {
        val dir = File(appContext.filesDir, "WalletSlot")
        return File(dir, "metadata.json")
    }

    private fun receiveSubaddressesFile(): File {
        val dir = File(appContext.filesDir, "WalletSlot")
        return File(dir, "receive_subaddresses.json")
    }

    private fun settingsFile(): File {
        val dir = File(appContext.filesDir, "WalletSlot")
        return File(dir, "settings.json")
    }

    private fun ensureParentDirExists(file: File) {
        val dir = file.parentFile ?: return
        if (!dir.exists()) dir.mkdirs()
    }

    private fun persistSettings(settings: StoredSettings) {
        runCatching {
            val f = settingsFile()
            ensureParentDirExists(f)
            val json = """
                {
                  "nodeUrl": "${settings.nodeUrl.replace("\"", "\\\"")}",
                  "savedAtMs": ${settings.savedAtMs}
                }
            """.trimIndent()
            f.writeText(json)
            _state.value = _state.value.copy(lastPersistedAtMs = System.currentTimeMillis())
        }.onFailure { t ->
            _state.value = _state.value.copy(lastError = "Failed to persist settings: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun readSettings(): StoredSettings {
        val f = settingsFile()
        val raw = f.readText()

        fun findString(key: String): String {
            val needle = "\"$key\""
            val idx = raw.indexOf(needle)
            if (idx < 0) throw IllegalStateException("Missing key: $key")
            val colon = raw.indexOf(':', idx)
            val firstQuote = raw.indexOf('"', colon + 1)
            val secondQuote = raw.indexOf('"', firstQuote + 1)
            if (firstQuote < 0 || secondQuote < 0) throw IllegalStateException("Invalid string for key: $key")
            return raw.substring(firstQuote + 1, secondQuote).replace("\\\"", "\"")
        }

        fun findLong(key: String): Long {
            val needle = "\"$key\""
            val idx = raw.indexOf(needle)
            if (idx < 0) throw IllegalStateException("Missing key: $key")
            val colon = raw.indexOf(':', idx)
            val end = raw.indexOfAny(charArrayOf(',', '\n', '\r', '}'), startIndex = colon + 1).let { if (it < 0) raw.length else it }
            return raw.substring(colon + 1, end).trim().toLong()
        }

        return StoredSettings(
            nodeUrl = migrateLegacyDefaultNodeUrl(findString("nodeUrl")),
            savedAtMs = runCatching { findLong("savedAtMs") }.getOrElse { 0L },
        )
    }

    private fun normalizeReceiveBook(book: ReceiveSubaddressBook?): ReceiveSubaddressBook {
        val baseEntries = book?.entries.orEmpty()
        val withPrimary = if (baseEntries.any { it.accountIndex == 0 && it.subaddressIndex == 0 }) {
            baseEntries
        } else {
            listOf(
                ReceiveSubaddressEntry(
                    accountIndex = 0,
                    subaddressIndex = 0,
                    label = "Primary",
                    createdAtMs = System.currentTimeMillis(),
                )
            ) + baseEntries
        }

        val deduped = withPrimary
            .filter { it.accountIndex == 0 }
            .distinctBy { "${it.accountIndex}:${it.subaddressIndex}" }
            .sortedWith(compareBy<ReceiveSubaddressEntry> { it.subaddressIndex }.thenBy { it.createdAtMs })

        val maxIndex = deduped.maxOfOrNull { it.subaddressIndex } ?: 0
        val next = maxOf(book?.nextSubaddressIndex ?: 1, maxIndex + 1)

        return ReceiveSubaddressBook(
            accountIndex = 0,
            nextSubaddressIndex = next,
            entries = deduped,
        )
    }

    private fun persistReceiveSubaddressBook(book: ReceiveSubaddressBook) {
        runCatching {
            val f = receiveSubaddressesFile()
            ensureParentDirExists(f)
            f.writeText(receiveBookJson.encodeToString(book))
        }.onFailure { t ->
            _state.value = _state.value.copy(lastError = "Failed to persist receive addresses: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /**
     * Shut down manager scope if you ever want to dispose it explicitly.
     * (Typically you keep this for the app lifetime.)
     */
    fun close() {
        refreshJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }
}
