package com.nexatrode.nexawal

import android.util.Log

import android.content.Context
import com.nexatrode.nexawal.logic.BalanceSnapshotPolicy
import com.nexatrode.nexawal.logic.CacheFileIO
import com.nexatrode.nexawal.logic.LastKnownGoodPolicy
import com.nexatrode.nexawal.logic.NetworkRouting
import com.nexatrode.nexawal.logic.ScanRecoveryPolicy
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
class RefreshStalledException(message: String) : IOException(message)

class WalletManager(
    private val appContext: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val fiatPrices = FiatPriceService(appContext)
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

        /**
         * True when [lastError] is caused by [RefreshStalledException] (refresh made no progress
         * for too long). Lets the UI detect the "stalled" state without doing English string
         * matching on [lastError].
         */
        val syncStalled: Boolean = false,
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
    private var catchUpJob: Job? = null
    private val refreshCancelRequested = AtomicBoolean(false)
    private val refreshInProgress = AtomicBoolean(false)
    private val cachePersistenceSuppressed = AtomicBoolean(false)
    private val sendGate = SendGate()

    // Cache export throttling:
    // - avoid redundant writes when exportCache returns identical bytes repeatedly (common during steady-state polling)
    // - avoid back-to-back writes within a short time window
    @Volatile private var lastExportCacheHash: Int? = null
    @Volatile private var lastExportCacheLen: Int? = null
    @Volatile private var lastExportAtMs: Long = 0L
    @Volatile private var didRewindEmptyHistory: Boolean = false

    private val receiveBookJson: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Default node URL (matches iOS default behavior).
     *
     * Public restricted monerod RPC via Caddy + Let's Encrypt on mini.
     */
    fun defaultNodeUrl(): String = "https://rpc.nexatrode.com"

    /**
     * Host:port form for Settings / status UI (iOS parity).
     * Scheme is added only when talking to the core via [normalizeNodeUrl].
     */
    fun nodeAddressForDisplay(url: String = currentNodeUrl()): String = url.trim()

    fun defaultNodeAddress(): String = nodeAddressForDisplay(defaultNodeUrl())

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
            throw IllegalStateException(appContext.getString(R.string.send_already_in_progress))
        }
        return try {
            block()
        } finally {
            sendGate.end()
        }
    }

    /**
     * Optional sibling-daemon retry for fee_rate failures that clearly
     * happened before spend/broadcast (mirrors iOS WalletManager).
     */
    private fun <T> withOptionalSiblingFeeRetry(nodeUrl: String, op: (String) -> T): T {
        return withOptionalSiblingFeeRetryReturningEndpoint(nodeUrl, op).first
    }

    /** Same as [withOptionalSiblingFeeRetry], but also returns the endpoint that succeeded. */
    private fun <T> withOptionalSiblingFeeRetryReturningEndpoint(
        nodeUrl: String,
        op: (String) -> T,
    ): Pair<T, String> {
        return try {
            Pair(op(nodeUrl), nodeUrl)
        } catch (t: Throwable) {
            val coreMsg = runCatching { WalletCore.lastErrorMessage() }.getOrNull().orEmpty()
            val fallback = SendSafety.shouldRetryViaSiblingMonerod(
                errorText = t.message.orEmpty(),
                coreMessage = coreMsg,
                endpoint = nodeUrl,
            ) ?: throw t
            Log.i(
                "WalletManager",
                "Fee RPC unavailable at $nodeUrl; retrying via sibling Monero RPC $fallback",
            )
            Pair(op(fallback), fallback)
        }
    }

    private fun filterJsonForSubaddressMinor(minor: Int): String {
        require(minor >= 0) { "subaddress minor must be >= 0" }
        return JSONObject()
            .put("subaddress_minor", minor)
            .toString()
    }

    private fun migrateLegacyDefaultNodeUrl(nodeUrl: String): String {
        return if (isShippedDefaultNodeUrl(nodeUrl)) defaultNodeUrl() else normalizeNodeUrl(nodeUrl)
    }

    /**
     * Live default or a previously shipped default. These are not user overrides, so app
     * updates can move [defaultNodeUrl] without getting stuck on the old string.
     */
    private fun isShippedDefaultNodeUrl(nodeUrl: String): Boolean {
        val trimmed = nodeUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return true
        val live = defaultNodeUrl().trim().trimEnd('/')
        if (trimmed.equals(live, ignoreCase = true)) return true
        val normalized = runCatching { normalizeNodeUrl(trimmed) }.getOrDefault(trimmed).trimEnd('/')
        if (normalized.equals(live, ignoreCase = true)) return true
        return when (normalized.lowercase()) {
            "https://node.sethforprivacy.com:443",
            "https://node.monerod.org:443",
            "http://mini.nexatrode.com:18092",
            "https://mini.nexatrode.com:18092",
            "http://mini.nexatrode.com:18089",
            "https://mini.nexatrode.com:18089",
            "http://rpc.nexatrode.com",
            "http://rpc.nexatrode.com:443",
            "https://rpc.nexatrode.com",
            "rpc.nexatrode.com",
            "http://cuprate.nexatrode.com",
            "https://cuprate.nexatrode.com",
            "cuprate.nexatrode.com" -> true
            // monero.nexatrode.com is a valid alternate endpoint — do not remap to the live default.
            else -> false
        }
    }

    /**
     * Normalize user input into a URL string that walletcore expects.
     *
     * Accepts full URLs only for user-entered clearnet nodes:
     * - "https://rpc.nexatrode.com"
     * - "http://127.0.0.1:18089"
     *
     * Trims whitespace and rejects blank values.
     */
    private fun normalizeNodeUrl(raw: String): String {
        val s = raw.trim()
        require(s.isNotEmpty()) { "node URL must not be empty" }
        return NetworkRouting.explicitNodeUrl(s)
            ?: NetworkRouting.normalizeUrl(s)
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
        val nodeUrl = migrateLegacyDefaultNodeUrl(s.nodeUrl.ifBlank { defaultNodeUrl() })
        _state.value = _state.value.copy(nodeUrl = nodeUrl, lastError = null, lastPersistedAtMs = s.savedAtMs)
        true
    }

    /**
     * Update the configured node URL in memory and persist it even if no wallet exists yet.
     *
     * This is what the Settings screen should call.
     */
    suspend fun setNodeUrl(newNodeUrl: String) = withContext(ioDispatcher) {
        val explicit = NetworkRouting.explicitNodeUrl(newNodeUrl.trim())
            ?: throw IllegalArgumentException(appContext.getString(R.string.node_url_scheme_error))
        val usingDefault = isShippedDefaultNodeUrl(explicit)
        val normalized = if (usingDefault) defaultNodeUrl() else explicit

        // Update state immediately so UI and flows use it.
        _state.value = _state.value.copy(nodeUrl = normalized, lastError = null)

        // Persist to settings.json (works even before a wallet exists).
        // Blank nodeUrl means "follow the live app default".
        persistSettings(
            StoredSettings(
                nodeUrl = if (usingDefault) "" else normalized,
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
                    nodeUrl = if (usingDefault) defaultNodeUrl() else normalized,
                    savedAtMs = System.currentTimeMillis()
                )
            )
        }.onFailure { t ->
            _state.value = _state.value.copy(lastError = appContext.getString(R.string.failed_persist_node_fmt, t.message ?: t.javaClass.simpleName))
        }
    }

    /**
     * Persist the node URL, stop any in-flight scan, and refresh against the new daemon.
     * Same-chain switches keep scan progress; they do not clear cache or rescan.
     */
    suspend fun applyNodeAndReconnect(newNodeUrl: String) {
        setNodeUrl(newNodeUrl)
        if (_state.value.walletId.isNullOrBlank()) return
        if (refreshInProgress.get() || refreshJob?.isActive == true) {
            cancelRefreshAndWait()
        }
        refreshWallet()
    }

    fun applyNodeAndReconnectInBackground(newNodeUrl: String): Job = scope.launch {
        runCatching { applyNodeAndReconnect(newNodeUrl) }
            .onFailure { t ->
                _state.value = _state.value.copy(lastError = t.message ?: t.javaClass.simpleName)
            }
    }

    fun rescanFromHeightInBackground(fromHeight: Long): Job = scope.launch {
        runCatching {
            if (refreshInProgress.get() || refreshJob?.isActive == true) {
                cancelRefreshAndWait()
            }
            rescanFromHeight(fromHeight)
        }.onFailure { t ->
            _state.value = _state.value.copy(lastError = t.message ?: t.javaClass.simpleName)
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
                lastError = appContext.getString(R.string.failed_derive_addr_fmt, t.message ?: t.toString())
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
                _state.value = _state.value.copy(lastError = appContext.getString(R.string.no_stored_metadata))
                return@launch
            }

            val meta = runCatching { readMetadata() }.getOrNull()
            if (meta == null) {
                _state.value = _state.value.copy(lastError = appContext.getString(R.string.failed_read_metadata))
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
        } else {
            // Constant wallet id can still have a leftover cache from a prior seed.
            runCatching {
                val cache = cacheFile(walletId, mainnet = mainnet)
                if (cache.exists()) cache.delete()
            }
        }

        MoneroConfig.setTrustedScannedHeight(appContext, 0L)
        MoneroConfig.setScanInterrupted(appContext, true)
        didRewindEmptyHistory = false
        cachePersistenceSuppressed.set(false)

        withContext(ioDispatcher) {
            WalletCore.openFromMnemonic(
                walletId = walletId,
                mnemonic = mnemonic.trim(),
                restoreHeight = restoreHeight,
                mainnet = mainnet,
            )
            // Constant wallet id leaves Occupied in-memory last_scanned at tip after replace.
            WalletCore.forceRescanFromHeight(walletId, restoreHeight)
        }

        // Derive primary address for UI, but do NOT publish walletId yet.
        // MainActivity switches to AppScaffold as soon as walletId is non-null, which disposes
        // WalletCreationScreen and cancels its coroutine — so publishing early would skip
        // persist + refreshWalletInBackground() and leave the UI stuck at "Waiting for network height".
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
                lastError = appContext.getString(R.string.failed_derive_addr_fmt, t.message ?: t.toString())
            )
        }.getOrNull()

        // Create/replace always starts a fresh scan. Do not import leftover cache for the
        // constant wallet id (that was the Mac empty-history-at-tip path).
        withContext(ioDispatcher) {
            recoverPendingPreparedSendBestEffort(walletId)
        }
        // Refresh status snapshot after open/import.
        updateStatusSnapshot(walletId)

        // Persist metadata for auto-load on next launch.
        if (persist) {
            withContext(ioDispatcher) {
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
        }

        // Start sync on the manager-owned scope BEFORE publishing walletId (which swaps the UI).
        refreshWalletInBackground()

        _state.value = _state.value.copy(
            walletId = walletId,
            nodeUrl = normalizedNodeUrl,
            walletAddress = derivedAddress,
            hasStoredWallet = runCatching { hasStoredWallet() }.getOrNull(),
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
        val walletId = _state.value.walletId ?: throw IllegalStateException(appContext.getString(R.string.no_wallet_open))
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

        // Extra guard: if a refresh is currently marked in progress, join it.
        // Wait briefly for the starter to publish `refreshJob` so callers never observe
        // "finished" during the flag-set → job-assign window.
        if (refreshInProgress.get()) {
            Log.i("WalletManager", "Refresh already in progress (flag); joining existing job walletId=$walletId")
            val deadline = System.nanoTime() + 5_000_000_000L
            while (refreshInProgress.get() &&
                (refreshJob == null || refreshJob?.isActive != true) &&
                System.nanoTime() < deadline
            ) {
                delay(10)
            }
            refreshJob?.let { job ->
                if (job.isActive) {
                    job.join()
                }
            }
            return _state.value.syncStatus ?: withContext(ioDispatcher) { WalletCore.syncStatus(walletId) }
        }

        // Capture the marker left by the previous run before marking this new refresh active.
        // Recovery must never treat the marker for the refresh being started as evidence that the
        // previous refresh was interrupted.
        val previousScanInterrupted = MoneroConfig.scanInterrupted(appContext)
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
            syncStalled = false,
            refreshTargetHeight = initialTargetHeight,
            gapLimit = gapLimit,
            accountGap = accountGap,
        )
        MoneroConfig.setScanInterrupted(appContext, true)
        startSyncForegroundService()

        val job = scope.launch(ioDispatcher) {
            try {
                maybeRewindInterruptedScan(walletId, previousScanInterrupted)
                withContext(ioDispatcher) {
                    // Apply per-wallet tuning before starting refresh. Scan request sizing is
                    // controlled by WalletCore's shared range/75/75 defaults unless explicitly
                    // overridden through WalletCore environment variables.
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
                        WalletCore.setEnv("WALLETCORE_SCAN_LOG", "0")
                    }.onFailure { t ->
                        Log.w(
                            "WalletManager",
                            "Failed to apply WALLETCORE_SCAN_LOG=0: ${t.message ?: t.javaClass.simpleName}"
                        )
                    }

                    Log.i(
                        "WalletManager",
                        "Refresh tuning applied: gapLimit=$gapLimit accountGap=$accountGap walletCoreScanDefaults=range/75/75 scanLog=0"
                    )

                    // A previous Kotlin waiter can finish before its native worker observes
                    // cancellation. Join that worker instead of racing a new async start.
                    if (WalletCore.refreshJobStatus(walletId).state == WalletCore.RefreshJobState.RUNNING) {
                        Log.i("WalletManager", "Waiting for previous native refresh worker walletId=$walletId")
                        waitForNativeRefreshStopped(walletId)
                    }

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
                val st = waitForRefreshCompletion(walletId = walletId, nodeUrl = nodeUrl)

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
                    syncStalled = false,
                    refreshTargetHeight = null,
                    gapLimit = null,
                    accountGap = null,
                )
                // Finalize history and the clean checkpoint before accepting a zero balance. A
                // mid-scan balance can be transient until later spends have been processed.
                refreshTransfersSnapshotNow(walletId)
                val emptyHistoryAtTip =
                    st.chainHeight > st.restoreHeight + 10_000L &&
                        _state.value.transfers.isEmpty()
                if (emptyHistoryAtTip) {
                    MoneroConfig.setTrustedScannedHeight(appContext, st.restoreHeight)
                    MoneroConfig.setScanInterrupted(appContext, true)
                    Log.w(
                        "WalletManager",
                        "scan complete but history empty; keeping interrupted and trusted=${st.restoreHeight}",
                    )
                } else {
                    MoneroConfig.setTrustedScannedHeight(appContext, st.lastScanned)
                    MoneroConfig.setScanInterrupted(appContext, false)
                    Log.i("WalletManager", "scan checkpoint trusted=${st.lastScanned} interrupted=false")
                }
                refreshBalanceSnapshotNow(
                    walletId = walletId,
                    allowAuthoritativeZero = !emptyHistoryAtTip,
                )
            } catch (ce: CancellationException) {
                // A coordinated cancellation remains visibly active until cancelRefreshAndWait()
                // observes that the native worker is terminal. Exporting or advertising idle here
                // would race the native scanner's final checkpoint.
                val st = withContext(ioDispatcher) {
                    runCatching { WalletCore.syncStatus(walletId) }.getOrNull()
                }
                if (st != null) {
                    _state.value = _state.value.copy(syncStatus = st)
                }
                if (!refreshCancelRequested.get()) {
                    withContext(ioDispatcher) {
                        exportCacheAndPersist(walletId)
                    }
                    _state.value = _state.value.copy(
                        refreshInProgress = false,
                        refreshStartedAtMs = null,
                        refreshLastProgressAtMs = null,
                        syncStalled = false,
                        refreshTargetHeight = null,
                        gapLimit = null,
                        accountGap = null,
                    )
                }
            } catch (t: Throwable) {
                // Best-effort: persist progress even on failure.
                withContext(ioDispatcher) {
                    exportCacheAndPersist(walletId)
                }
                if (t !is CancellationException) {
                    MoneroConfig.setScanInterrupted(appContext, true)
                }
                val msg = t.message ?: t.javaClass.simpleName
                _state.value = _state.value.copy(
                    refreshInProgress = false,
                    refreshStartedAtMs = null,
                    refreshLastProgressAtMs = null,
                    lastError = msg,
                    syncStalled = t is RefreshStalledException,
                    refreshTargetHeight = null,
                    gapLimit = null,
                    accountGap = null,
                )
            } finally {
                if (!refreshCancelRequested.get()) {
                    refreshInProgress.set(false)
                    stopSyncForegroundService()
                }
            }
        }

        refreshJob = job // publish before join so concurrent callers can attach
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

        val walletId = _state.value.walletId ?: throw IllegalStateException(appContext.getString(R.string.no_wallet_open))
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
        cachePersistenceSuppressed.set(false)
        didRewindEmptyHistory = false

        _state.value = _state.value.copy(
            syncStatus = currentStatus?.copy(
                chainHeight = maxOf(currentStatus.chainHeight, fromHeight),
                lastScanned = fromHeight,
                restoreHeight = fromHeight,
            ),
            balance = WalletCore.Balance(0L, 0L),
            transfers = emptyList(),
            transfersJson = null,
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
        val walletId = _state.value.walletId ?: throw IllegalStateException(appContext.getString(R.string.no_wallet_open))

        cancelRefreshAndWait()

        withContext(ioDispatcher) {
            val cache = cacheFile(walletId, mainnet = true)
            if (cache.exists()) {
                cache.delete()
            }
        }

        lastExportCacheHash = null
        lastExportCacheLen = null
        lastExportAtMs = 0L
        cachePersistenceSuppressed.set(true)

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
     * - keeps polling until WalletCore reports a terminal state
     * - exports cache only after the native refresh has stopped
     */
    fun cancelRefresh() {
        scope.launch { cancelRefreshAndWait() }
    }

    private suspend fun cancelRefreshAndWait() {
        val walletId = _state.value.walletId ?: return
        val nativeJob = withContext(ioDispatcher) {
            runCatching { WalletCore.refreshJobStatus(walletId) }.getOrNull()
        }
        if (!refreshInProgress.get() && nativeJob?.state != WalletCore.RefreshJobState.RUNNING) return

        // Keep interrupted=true so a cancelled/partial cache is never treated as a clean sync.
        // (Previously this cleared the flag, which made "WALLET SYNCED" + incomplete history common.)
        MoneroConfig.setScanInterrupted(appContext, true)
        refreshCancelRequested.set(true)

        withContext(ioDispatcher) {
            runCatching {
                WalletCore.refreshCancel(walletId)
            }.onFailure { t ->
                _state.value = _state.value.copy(lastError = appContext.getString(R.string.refresh_cancel_failed_fmt, t.message ?: t.javaClass.simpleName))
            }
        }

        runCatching { waitForNativeRefreshStopped(walletId) }
            .onFailure { t ->
                _state.value = _state.value.copy(lastError = t.message ?: t.javaClass.simpleName)
            }
        withContext(ioDispatcher) { exportCacheAndPersist(walletId) }

        refreshInProgress.set(false)
        stopSyncForegroundService()

        _state.value = _state.value.copy(
            refreshInProgress = false,
            refreshStartedAtMs = null,
            refreshLastProgressAtMs = null,
            syncStalled = false,
            refreshTargetHeight = null,
            gapLimit = null,
            accountGap = null,
        )
    }

    private suspend fun waitForNativeRefreshStopped(
        walletId: String,
        timeoutMs: Long = 20_000L,
        pollIntervalMs: Long = 50L,
    ): WalletCore.RefreshJobStatus {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val status = withContext(ioDispatcher) { WalletCore.refreshJobStatus(walletId) }
            when (status.state) {
                WalletCore.RefreshJobState.IDLE -> return status
                WalletCore.RefreshJobState.FAILED -> throw IOException(
                    status.error ?: "Native refresh failed without an error message"
                )
                WalletCore.RefreshJobState.RUNNING -> Unit
            }
            if (System.currentTimeMillis() >= deadline) {
                throw IOException("Timed out waiting for the native refresh worker to stop")
            }
            delay(pollIntervalMs.coerceAtLeast(10L))
        }
    }

    /**
     * Snapshot state for backgrounding. (Best-effort)
     *
     * This does NOT cancel refresh.
     */
    fun snapshotState() {
        val walletId = _state.value.walletId ?: return
        if (refreshInProgress.get() || _state.value.refreshInProgress) {
            MoneroConfig.setScanInterrupted(appContext, true)
        }
        scope.launch {
            withContext(ioDispatcher) {
                exportCacheAndPersist(walletId)
            }
        }
    }

    private fun startSyncForegroundService() {
        val detail = appContext.getString(R.string.sync_notification_progress_fmt, 0)
        runCatching {
            WalletSyncForegroundService.start(appContext, percent = 0, detail = detail)
        }.onFailure { t ->
            Log.w(
                "WalletManager",
                "Failed to start sync foreground service: ${t.message ?: t.javaClass.simpleName}",
            )
        }
    }

    private fun stopSyncForegroundService() {
        runCatching {
            WalletSyncForegroundService.stop(appContext)
        }.onFailure { t ->
            Log.w(
                "WalletManager",
                "Failed to stop sync foreground service: ${t.message ?: t.javaClass.simpleName}",
            )
        }
    }

    private fun updateSyncForegroundService(st: WalletCore.SyncStatus) {
        val target = _state.value.refreshTargetHeight?.takeIf { it > 0L }
            ?: st.chainHeight.takeIf { it > st.restoreHeight }
            ?: 0L
        val restore = st.restoreHeight
        val percent = if (target > restore) {
            val span = (target - restore).toDouble()
            val done = (st.lastScanned - restore).toDouble().coerceAtLeast(0.0)
            ((done / span) * 100.0).toInt().coerceIn(0, 99)
        } else {
            0
        }
        val detail = appContext.getString(R.string.sync_notification_progress_fmt, percent)
        runCatching {
            WalletSyncForegroundService.update(appContext, percent, detail)
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

    private suspend fun refreshBalanceSnapshotNow(
        walletId: String,
        allowAuthoritativeZero: Boolean = false,
    ) {
        val bal = withContext(ioDispatcher) {
            runCatching { WalletCore.getBalance(walletId) }.getOrNull()
        }
        if (bal != null) {
            applyBalanceSnapshot(
                balance = bal,
                allowAuthoritativeZero =
                    allowAuthoritativeZero || isZeroBalanceAuthoritative(_state.value),
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
            val previous = _state.value
            val decision = LastKnownGoodPolicy.choose(
                previous.transfers,
                runCatching { TransferHistoryJson.decode(json, walletId).transfers },
            )
            if (decision.accepted) {
                val parsed = decision.value
                _state.value = previous.copy(
                    transfersJson = json,
                    transfers = parsed,
                    transfersParseError = null,
                    lastTransfersRefreshAtMs = System.currentTimeMillis(),
                )
                fiatPrices.recordSeenTransfers(
                    parsed.map { FiatSeenTransfer(txid = it.txid, timestampSeconds = it.timestamp?.takeIf { ts -> ts > 0L }) },
                )
                if (parsed.isNotEmpty()) {
                    didRewindEmptyHistory = false
                }
            } else {
                _state.value = previous.copy(
                    transfersParseError = decision.errorMessage,
                    lastTransfersRefreshAtMs = System.currentTimeMillis(),
                )
                Log.w(
                    "WalletManager",
                    "Keeping last-known-good transaction history after parse failure: ${decision.errorMessage}",
                )
            }
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
        if (MoneroConfig.scanInterrupted(appContext) || state.refreshInProgress) return false
        val observedNetworkTip = st.chainHeight > st.restoreHeight || st.chainTime > 0
        val syncedWithinTolerance = st.chainHeight > 0 && st.lastScanned + 3 >= st.chainHeight
        val trusted = MoneroConfig.trustedScannedHeight(appContext)
        val checkpointOk = st.lastScanned <= trusted + 3L
        if (!observedNetworkTip || !syncedWithinTolerance || !checkpointOk) return false
        if (st.chainHeight > st.restoreHeight + 10_000L && state.transfers.isEmpty()) return false
        return true
    }

    private fun applyBalanceSnapshot(
        balance: WalletCore.Balance,
        allowAuthoritativeZero: Boolean,
    ) {
        val current = _state.value
        val knownTotal = maxOf(current.balance?.totalPiconero ?: 0L, 0L)
        val knownUnlocked = maxOf(current.balance?.unlockedPiconero ?: 0L, 0L)
        val decision = BalanceSnapshotPolicy.decide(
            knownTotal = knownTotal,
            knownUnlocked = knownUnlocked,
            proposedTotal = balance.totalPiconero,
            proposedUnlocked = balance.unlockedPiconero,
            refreshInProgress = current.refreshInProgress,
            scanInterrupted = MoneroConfig.scanInterrupted(appContext),
            allowAuthoritativeZero = allowAuthoritativeZero,
        )

        if (!decision.apply) {
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
            balanceIsStaleWhileSyncing = decision.staleWhileSyncing,
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
        val walletId = _state.value.walletId ?: throw IllegalStateException(appContext.getString(R.string.no_wallet_open))
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
        val walletId = _state.value.walletId ?: throw IllegalStateException(appContext.getString(R.string.no_wallet_open))

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
        val walletId = _state.value.walletId ?: throw IllegalStateException(appContext.getString(R.string.no_wallet_open))
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
     * Exact (unfiltered) sends use prepare → durable persist → relay so a crash between
     * signing and broadcast can be retried idempotently. Filtered / sweep paths still use
     * fire-and-forget send (no prepare-with-filter / prepare-sweep in the core ABI yet).
     */
    suspend fun send(
        toAddress: String,
        amountPiconero: Long,
        ringLen: Int = 16,
    ): SendJson.SendResult = withSendLock {
        val walletId = _state.value.walletId ?: throw IllegalStateException(appContext.getString(R.string.no_wallet_open))
        val nodeUrl = resolveBroadcastNodeUrl()

        val res = withContext(ioDispatcher) {
            applyProxyEnv(forBroadcast = true)
            completePendingPreparedSend(walletId, preferredNodeUrl = nodeUrl)?.let { recovered ->
                return@withContext SendJson.SendResult(txid = recovered.txid, fee = recovered.fee)
            }

            val (preparedRaw, usedEndpoint) = withOptionalSiblingFeeRetryReturningEndpoint(nodeUrl) { endpoint ->
                WalletCore.prepareSendJson(
                    walletId = walletId,
                    toAddress = toAddress,
                    amountPiconero = amountPiconero,
                    ringLen = ringLen,
                    nodeUrl = endpoint,
                )
            }
            val prepared = SendJson.decodePreparedSend(preparedRaw)
            persistAndRelayPrepared(walletId, usedEndpoint, prepared)
                .let { SendJson.SendResult(txid = it.txid, fee = it.fee) }
        }

        // Persist immediately so pending outgoing survives restart before next refresh.
        withContext(ioDispatcher) {
            exportCacheAndPersist(walletId)
        }

        _state.value = _state.value.copy(
            lastSendResult = res,
            lastError = null,
            lastSendOrSweepAtMs = System.currentTimeMillis(),
        )
        fiatPrices.recordSend(res.txid)

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
        val walletId = _state.value.walletId ?: throw IllegalStateException(appContext.getString(R.string.no_wallet_open))
        val nodeUrl = resolveBroadcastNodeUrl()

        val res = withContext(ioDispatcher) {
            applyProxyEnv(forBroadcast = true)
            completePendingPreparedSend(walletId, preferredNodeUrl = nodeUrl)?.let { recovered ->
                return@withContext SendJson.SendResult(txid = recovered.txid, fee = recovered.fee)
            }

            val (preparedRaw, usedEndpoint) = withOptionalSiblingFeeRetryReturningEndpoint(nodeUrl) { endpoint ->
                WalletCore.prepareSendJsonWithFilter(
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
            persistAndRelayPrepared(walletId, usedEndpoint, SendJson.decodePreparedSend(preparedRaw))
                .let { SendJson.SendResult(txid = it.txid, fee = it.fee) }
        }

        withContext(ioDispatcher) {
            exportCacheAndPersist(walletId)
        }

        _state.value = _state.value.copy(
            lastSendResult = res,
            lastError = null,
            lastSendOrSweepAtMs = System.currentTimeMillis(),
        )
        fiatPrices.recordSend(res.txid)

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
        val walletId = _state.value.walletId ?: throw IllegalStateException(appContext.getString(R.string.no_wallet_open))
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
        val walletId = _state.value.walletId ?: throw IllegalStateException(appContext.getString(R.string.no_wallet_open))
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
     * Uses prepare → durable persist → relay (same crash window protection as exact send).
     */
    suspend fun sweep(
        toAddress: String,
        ringLen: Int = 16,
    ): SendJson.SweepSendResult = withSendLock {
        val walletId = _state.value.walletId ?: throw IllegalStateException(appContext.getString(R.string.no_wallet_open))
        val nodeUrl = resolveBroadcastNodeUrl()

        val res = withContext(ioDispatcher) {
            applyProxyEnv(forBroadcast = true)
            completePendingPreparedSend(walletId, preferredNodeUrl = nodeUrl)?.let { recovered ->
                return@withContext SendJson.SweepSendResult(
                    txid = recovered.txid,
                    amount = recovered.amount,
                    fee = recovered.fee,
                )
            }

            val (preparedRaw, usedEndpoint) = withOptionalSiblingFeeRetryReturningEndpoint(nodeUrl) { endpoint ->
                WalletCore.prepareSweepJson(
                    walletId = walletId,
                    toAddress = toAddress,
                    ringLen = ringLen,
                    nodeUrl = endpoint,
                )
            }
            persistAndRelayPrepared(walletId, usedEndpoint, SendJson.decodePreparedSend(preparedRaw))
                .let {
                    SendJson.SweepSendResult(txid = it.txid, amount = it.amount, fee = it.fee)
                }
        }

        withContext(ioDispatcher) {
            exportCacheAndPersist(walletId)
        }

        _state.value = _state.value.copy(
            lastSweepSendResult = res,
            lastError = null,
            lastSendOrSweepAtMs = System.currentTimeMillis(),
        )
        fiatPrices.recordSend(res.txid)

        refreshWalletDataSnapshotsNow(walletId)

        res
    }

    suspend fun sweep(
        fromSubaddressMinor: Int,
        toAddress: String,
        ringLen: Int = 16,
    ): SendJson.SweepSendResult = withSendLock {
        require(fromSubaddressMinor >= 0) { "fromSubaddressMinor must be >= 0" }
        val walletId = _state.value.walletId ?: throw IllegalStateException(appContext.getString(R.string.no_wallet_open))
        val nodeUrl = resolveBroadcastNodeUrl()

        val res = withContext(ioDispatcher) {
            applyProxyEnv(forBroadcast = true)
            completePendingPreparedSend(walletId, preferredNodeUrl = nodeUrl)?.let { recovered ->
                return@withContext SendJson.SweepSendResult(
                    txid = recovered.txid,
                    amount = recovered.amount,
                    fee = recovered.fee,
                )
            }

            val (preparedRaw, usedEndpoint) = withOptionalSiblingFeeRetryReturningEndpoint(nodeUrl) { endpoint ->
                WalletCore.prepareSweepJsonWithFilter(
                    walletId = walletId,
                    toAddress = toAddress,
                    filterJson = filterJsonForSubaddressMinor(fromSubaddressMinor),
                    ringLen = ringLen,
                    nodeUrl = endpoint,
                )
            }
            persistAndRelayPrepared(walletId, usedEndpoint, SendJson.decodePreparedSend(preparedRaw))
                .let {
                    SendJson.SweepSendResult(txid = it.txid, amount = it.amount, fee = it.fee)
                }
        }

        withContext(ioDispatcher) {
            exportCacheAndPersist(walletId)
        }

        _state.value = _state.value.copy(
            lastSweepSendResult = res,
            lastError = null,
            lastSendOrSweepAtMs = System.currentTimeMillis(),
        )
        fiatPrices.recordSend(res.txid)

        refreshWalletDataSnapshotsNow(walletId)

        res
    }

    private fun isTerminalRefreshCoreError(message: String): Boolean {
        val normalized = message.lowercase()
        return normalized.contains("contiguous_scannable_blocks timeout/disconnect") ||
            normalized.contains("invalid node") ||
            normalized.contains("failed to connect daemon") ||
            normalized.contains("response wasn't the expected json")
    }

    /**
     * True when a wallet is open, behind tip, and no refresh is running.
     * Used to auto-resume sync after stall failure / app backgrounding.
     */
    fun shouldAutoResumeRefresh(): Boolean {
        if (refreshInProgress.get() || _state.value.refreshInProgress) return false
        if (_state.value.walletId == null) return false
        if (MoneroConfig.scanInterrupted(appContext)) return true
        val st = _state.value.syncStatus ?: return false
        val trusted = MoneroConfig.trustedScannedHeight(appContext)
        // Cursor ran ahead of the last clean checkpoint (cancel/quit partial scan).
        if (st.lastScanned > trusted + 3L) return true
        val tipKnown = st.chainHeight > st.restoreHeight || st.chainTime > 0L
        if (!tipKnown) return true // still waiting for tip — worth another try
        if (st.lastScanned + 3L >= st.chainHeight &&
            st.chainHeight > st.restoreHeight + 10_000L &&
            _state.value.transfers.isEmpty()
        ) {
            return true
        }
        return st.lastScanned + 3L < st.chainHeight
    }

    /** If a killed refresh left lastScanned ≈ tip, rewind to the last completed checkpoint. */
    private fun maybeRewindInterruptedScan(
        walletId: String,
        previousScanInterrupted: Boolean,
    ) {
        val st = _state.value.syncStatus
            ?: runCatching { WalletCore.syncStatus(walletId) }.getOrNull()
            ?: return
        val trusted = MoneroConfig.trustedScannedHeight(appContext)
        // Metadata is the user's durable restore choice. A cache produced by the old refresh bug
        // may have raised the core's internal restore height to the tip while leaving metadata
        // untouched, so prefer the earlier of the two for recovery.
        val persistedRestoreHeight = persistedRestoreHeightOrNull()
        val recoveryRestoreHeight = ScanRecoveryPolicy.recoveryRestoreHeight(
            coreRestoreHeight = st.restoreHeight,
            persistedRestoreHeight = persistedRestoreHeight,
        )
        val decision = ScanRecoveryPolicy.decide(
            previousScanInterrupted = previousScanInterrupted,
            lastScanned = st.lastScanned,
            chainHeight = st.chainHeight,
            chainTime = st.chainTime,
            restoreHeight = recoveryRestoreHeight,
            trustedScannedHeight = trusted,
            transfersEmpty = _state.value.transfers.isEmpty(),
            didRewindEmptyHistory = didRewindEmptyHistory,
        ) ?: return
        if (decision.emptyHistoryAtTip) {
            didRewindEmptyHistory = true
        }
        Log.w(
            "WalletManager",
            "incomplete scan looks at tip; rewinding cursor from ${st.lastScanned} to ${decision.rewindHeight} (tip=${st.chainHeight} coreRestore=${st.restoreHeight} persistedRestore=$persistedRestoreHeight trusted=$trusted previousInterrupted=$previousScanInterrupted emptyHistory=${decision.emptyHistoryAtTip})",
        )
        runCatching {
            WalletCore.forceRescanFromHeight(walletId, decision.rewindHeight)
        }.onFailure { t ->
            Log.w(
                "WalletManager",
                "rewindScanCursor failed: ${t.message ?: t.javaClass.simpleName}",
            )
        }
    }

    fun refreshWalletInBackgroundIfNeeded(reason: String = "auto-resume"): Job? {
        if (!shouldAutoResumeRefresh()) return null
        Log.i("WalletManager", "Auto-resuming refresh ($reason) walletId=${_state.value.walletId}")
        _state.value = _state.value.copy(lastError = null, syncStalled = false)
        return refreshWalletInBackground()
    }

    /**
     * While the UI is visible, periodically probe the daemon tip and start a refresh if we are
     * behind. Covers sitting on the wallet screen after tip advances — not a closed-app timer.
     */
    fun startForegroundCatchUp() {
        if (catchUpJob?.isActive == true) return
        catchUpJob = scope.launch {
            while (isActive) {
                runCatching {
                    checkTipAndAutoResume(reason = "foreground-catch-up")
                }.onFailure { t ->
                    Log.w(
                        "WalletManager",
                        "Foreground catch-up failed: ${t.message ?: t.javaClass.simpleName}",
                    )
                }
                delay(FOREGROUND_CATCH_UP_INTERVAL_MS)
            }
        }
    }

    fun stopForegroundCatchUp() {
        catchUpJob?.cancel()
        catchUpJob = null
    }

    /**
     * Probe daemon height, fold a higher tip into [UiState.syncStatus], then auto-resume if behind.
     */
    suspend fun checkTipAndAutoResume(reason: String) {
        if (refreshInProgress.get() || _state.value.refreshInProgress) return
        val walletId = _state.value.walletId ?: return
        val nodeUrl = _state.value.nodeUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return

        val tip = withContext(ioDispatcher) {
            runCatching { probeDaemonHeightViaOkHttp(nodeUrl) }.getOrNull()
        }
        if (tip != null && tip > 0L) {
            val current = _state.value.syncStatus
            if (current != null && tip > current.chainHeight) {
                Log.i(
                    "WalletManager",
                    "Catch-up tip advanced: chainHeight ${current.chainHeight} -> $tip ($reason)",
                )
                _state.value = _state.value.copy(
                    syncStatus = current.copy(chainHeight = tip),
                )
            } else if (current == null) {
                val seeded = withContext(ioDispatcher) {
                    runCatching { WalletCore.syncStatus(walletId) }.getOrNull()
                }
                if (seeded != null) {
                    _state.value = _state.value.copy(
                        syncStatus = seeded.copy(chainHeight = maxOf(seeded.chainHeight, tip)),
                    )
                }
            }
        }

        refreshWalletInBackgroundIfNeeded(reason = reason)
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
        nodeUrl: String,
        pollIntervalMs: Long = 1_000L,
        slowFetchWarningMs: Long = 30_000L,
        hardStallTimeoutMs: Long = 90_000L,
    ): WalletCore.SyncStatus = withContext(ioDispatcher) {
        var targetHeight: Long? = null
        var lastScannedSnapshot: Long = 0
        val refreshStartMs = System.currentTimeMillis()
        var lastProgressAtMs = refreshStartMs
        var slowFetchWarningLogged = false

        // Periodic persistence while refresh is running.
        // Match iOS: exportCache is expensive (wallet lock + large alloc + disk write), so avoid a
        // short wall-clock cadence. Require both elapsed time and meaningful scan progress.
        var lastPersistAtMs = 0L
        var lastPersistedScannedHeight = 0L
        val persistIntervalMs = 120_000L
        val persistBlockDelta = 1_000L

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

            val nativeJob = WalletCore.refreshJobStatus(walletId)
            if (nativeJob.state == WalletCore.RefreshJobState.FAILED) {
                exportCacheAndPersist(walletId)
                throw IOException(
                    nativeJob.error ?: "Native refresh failed without an error message"
                )
            }
            val st = WalletCore.syncStatus(walletId)

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
                updateSyncForegroundService(st)
            }

            // Periodic cache persistence (iOS parity: 120s + 1000 blocks).
            if (st.lastScanned > 0 &&
                nowMs - lastPersistAtMs >= persistIntervalMs &&
                st.lastScanned >= lastPersistedScannedHeight + persistBlockDelta
            ) {
                exportCacheAndPersist(walletId)
                Log.i(
                    "WalletManager",
                    "CACHE_EXPORT reason: periodic walletId=$walletId scanned=${st.lastScanned}",
                )
                lastPersistAtMs = nowMs
                lastPersistedScannedHeight = st.lastScanned
            }

            // Completion condition.
            // Only evaluate completion once we have captured a stable target height.
            targetHeight?.let { target ->
                val effectiveTarget = maxOf(target, st.restoreHeight)
                if (effectiveTarget > 0 &&
                    st.lastScanned >= effectiveTarget &&
                    nativeJob.state == WalletCore.RefreshJobState.IDLE
                ) {
                    return@withContext st
                }
            }

            if (nativeJob.state == WalletCore.RefreshJobState.IDLE &&
                targetHeight != null &&
                st.lastScanned < maxOf(targetHeight!!, st.restoreHeight)
            ) {
                exportCacheAndPersist(walletId)
                throw IOException(
                    "Native refresh stopped before reaching its target " +
                        "(lastScanned=${st.lastScanned}, target=$targetHeight)"
                )
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
                Log.e(
                    "WalletManager",
                    "STALL: refresh appears stuck (>${hardStallTimeoutMs}ms); keeping WalletCore scan profile unchanged " +
                        "walletId=$walletId " +
                        "lastScanned=${st.lastScanned} restoreHeight=${st.restoreHeight} chainHeight=${st.chainHeight} " +
                        "target=$tip remaining=$remaining nativeJob=${nativeJob.state}"
                )

                exportCacheAndPersist(walletId)
                throw RefreshStalledException(
                    "Refresh stalled (>${hardStallTimeoutMs}ms) lastScanned=${st.lastScanned} chainHeight=${st.chainHeight}"
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

    /** Durable prepared-send payload so relay can resume after a crash mid-broadcast. */
    private fun preparedFile(walletId: String, mainnet: Boolean = true): File {
        val netDir = if (mainnet) "mainnet" else "stagenet"
        val dir = File(appContext.filesDir, "WalletCaches/$netDir")
        return File(dir, "$walletId.prepared.json")
    }

    private fun persistPendingPrepared(
        walletId: String,
        nodeUrl: String,
        prepared: SendJson.PreparedSend,
    ) {
        val f = preparedFile(walletId, mainnet = true)
        ensureCacheDirExists(f)
        val envelope = SendJson.PendingPreparedEnvelope(nodeUrl = nodeUrl, prepared = prepared)
        CacheFileIO.writeAtomically(
            f,
            SendJson.encodePendingPreparedEnvelope(envelope).toByteArray(Charsets.UTF_8),
        )
        Log.i("WalletManager", "Persisted prepared send txid=${prepared.txid} to ${f.name}")
    }

    private fun clearPendingPrepared(walletId: String) {
        val f = preparedFile(walletId, mainnet = true)
        if (f.exists()) {
            f.delete()
            Log.i("WalletManager", "Cleared prepared send file ${f.name}")
        }
    }

    private fun loadPendingPrepared(walletId: String): SendJson.PendingPreparedEnvelope? {
        val f = preparedFile(walletId, mainnet = true)
        val raw = CacheFileIO.readTextIfPresent(f) ?: return null
        return try {
            SendJson.decodePendingPreparedEnvelope(raw)
        } catch (t: Throwable) {
            Log.w("WalletManager", "Failed to decode prepared send file: ${t.message}")
            throw IllegalStateException(
                "Pending send recovery data exists but cannot be decoded. New sends are blocked until it is recovered or removed: ${t.message ?: t.javaClass.simpleName}",
                t,
            )
        }
    }

    private data class RecoveredPreparedSend(
        val txid: String,
        val amount: Long,
        val fee: Long,
    )

    /**
     * If a prepared payload is on disk, relay it (idempotent) and return that result.
     * Callers must not construct a second transaction in the same user action.
     * Throws on relay failure so we do not construct a conflicting second tx.
     */
    private fun completePendingPreparedSend(
        walletId: String,
        preferredNodeUrl: String,
    ): RecoveredPreparedSend? {
        val pending = loadPendingPrepared(walletId) ?: return null
        applyProxyEnv(forBroadcast = true)
        val endpoint = pending.nodeUrl.ifBlank { preferredNodeUrl }
        Log.i(
            "WalletManager",
            "Recovering pending prepared send txid=${pending.prepared.txid} via $endpoint",
        )
        val relayRaw = WalletCore.relayPreparedJson(
            walletId = walletId,
            preparedJson = SendJson.encodePreparedSend(pending.prepared),
            nodeUrl = endpoint,
        )
        val relay = SendJson.decodeRelayResult(relayRaw)
        clearPendingPrepared(walletId)
        exportCacheAndPersist(walletId)
        Log.i(
            "WalletManager",
            "Recovered pending prepared send txid=${relay.txid} status=${relay.status}",
        )
        return RecoveredPreparedSend(
            txid = relay.txid,
            amount = pending.prepared.amount,
            fee = pending.prepared.fee,
        )
    }

    private fun persistAndRelayPrepared(
        walletId: String,
        usedEndpoint: String,
        prepared: SendJson.PreparedSend,
    ): SendJson.PreparedSend {
        persistPendingPrepared(walletId, usedEndpoint, prepared)
        val relayRaw = WalletCore.relayPreparedJson(
            walletId = walletId,
            preparedJson = SendJson.encodePreparedSend(prepared),
            nodeUrl = usedEndpoint,
        )
        val relay = SendJson.decodeRelayResult(relayRaw)
        clearPendingPrepared(walletId)
        Log.i(
            "WalletManager",
            "prepare→relay ok txid=${relay.txid} status=${relay.status} fee=${prepared.fee} endpoint=$usedEndpoint",
        )
        return prepared.copy(txid = relay.txid)
    }

    private fun recoverPendingPreparedSendBestEffort(walletId: String) {
        runCatching {
            completePendingPreparedSend(
                walletId = walletId,
                preferredNodeUrl = resolveBroadcastNodeUrl(),
            )
        }.onFailure { t ->
            Log.w(
                "WalletManager",
                "Pending prepared send recovery deferred: ${t.message ?: t.toString()}",
            )
        }
    }

    private fun ensureCacheDirExists(file: File) {
        val dir = file.parentFile ?: return
        if (!dir.exists()) dir.mkdirs()
    }

    companion object {
        const val DEFAULT_WALLET_ID: String = "main_wallet"

        // Scan request sizing is provided by WalletCore's shared range/75/75 defaults.

        /** How often to re-probe tip and auto-resume while the app is in the foreground. */
        private const val FOREGROUND_CATCH_UP_INTERVAL_MS = 60_000L
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

        val bytes = runCatching { f.readBytes() }.getOrElse { error ->
            val quarantined = quarantineRejectedCache(
                file = f,
                walletId = walletId,
                reason = "read failed: ${error.message ?: error.javaClass.simpleName}",
            )
            Log.w(
                "WalletManager",
                "CACHE_IMPORT failed to read bytes walletId=$walletId file=${f.absolutePath} quarantined=${quarantined?.absolutePath}",
            )
            return
        }
        if (bytes.isEmpty()) {
            val quarantined = quarantineRejectedCache(f, walletId, "empty cache")
            Log.w(
                "WalletManager",
                "CACHE_IMPORT empty cache file walletId=$walletId file=${f.absolutePath} quarantined=${quarantined?.absolutePath}",
            )
            return
        }

        runCatching {
            WalletCore.importCache(walletId, bytes)
        }.onFailure { t ->
            // Best-effort only: don't fail opening wallet due to bad cache.
            val msg = "cache import failed: ${t.message ?: t.javaClass.simpleName}"
            val quarantined = quarantineRejectedCache(f, walletId, msg)
            Log.w(
                "WalletManager",
                "CACHE_IMPORT error walletId=$walletId bytes=${bytes.size} err=$msg quarantined=${quarantined?.absolutePath}",
            )
            _state.value = _state.value.copy(lastError = appContext.getString(R.string.cache_import_failed_fmt, t.message ?: t.javaClass.simpleName))
            return
        }

        val stAfter = runCatching { WalletCore.syncStatus(walletId) }.getOrNull()
        Log.i(
            "WalletManager",
            "CACHE_IMPORT ok walletId=$walletId importedBytes=${bytes.size} " +
                "syncStatusAfter(chainHeight=${stAfter?.chainHeight} lastScanned=${stAfter?.lastScanned} restoreHeight=${stAfter?.restoreHeight})"
        )
        // Re-apply after import so subaddress lookahead is registered on the restored scanner.
        val gap = MoneroConfig.gapLimit(appContext)
        runCatching {
            WalletCore.setGapLimit(walletId = walletId, gapLimit = gap)
        }.onFailure { t ->
            Log.w("WalletManager", "setGapLimit after cache import failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun exportCacheAndPersist(walletId: String) {
        if (cachePersistenceSuppressed.get()) {
            Log.i("WalletManager", "CACHE_EXPORT suppressed after explicit clear walletId=$walletId")
            return
        }
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
            CacheFileIO.writeAtomically(f, cache)

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
            _state.value = _state.value.copy(lastError = appContext.getString(R.string.cache_export_failed_fmt, t.message ?: t.javaClass.simpleName))
        }
    }

    private fun quarantineRejectedCache(file: File, walletId: String, reason: String): File? {
        return runCatching { CacheFileIO.quarantineRejected(file) }
            .onSuccess { quarantined ->
                if (quarantined != null) {
                    _state.value = _state.value.copy(cacheInfo = null)
                    Log.w(
                        "WalletManager",
                        "CACHE_QUARANTINE walletId=$walletId reason=$reason movedTo=${quarantined.absolutePath}",
                    )
                }
            }
            .onFailure { error ->
                Log.w(
                    "WalletManager",
                    "CACHE_QUARANTINE failed walletId=$walletId reason=$reason file=${file.absolutePath} err=${error.message ?: error.javaClass.simpleName}",
                )
            }
            .getOrNull()
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
                lastError = appContext.getString(R.string.failed_read_metadata_fmt, t.message ?: t.javaClass.simpleName)
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
                lastError = appContext.getString(R.string.failed_open_wallet_fmt, t.message ?: t.javaClass.simpleName)
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
                lastError = appContext.getString(R.string.failed_derive_addr_fmt, t.message ?: t.toString())
            )
        }.getOrNull()

        val settingsNode = _state.value.nodeUrl?.takeIf { it.isNotBlank() }
        val resolvedNode = settingsNode ?: meta.nodeUrl
        if (settingsNode != null && meta.nodeUrl != settingsNode) {
            runCatching {
                persistMetadata(
                    meta.copy(
                        nodeUrl = settingsNode,
                        savedAtMs = System.currentTimeMillis(),
                    )
                )
            }
        }

        _state.value = _state.value.copy(
            walletId = meta.walletId,
            nodeUrl = resolvedNode,
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
        recoverPendingPreparedSendBestEffort(meta.walletId)
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
            val p = preparedFile(DEFAULT_WALLET_ID, mainnet = true)
            if (p.exists()) p.delete()
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
        MoneroConfig.setTrustedScannedHeight(appContext, 0L)
        MoneroConfig.setScanInterrupted(appContext, true)
        didRewindEmptyHistory = false
    }

    private fun persistMetadata(meta: StoredWalletMetadata) {
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
        CacheFileIO.writeAtomically(f, json.toString(2).toByteArray(Charsets.UTF_8))
        _state.value = _state.value.copy(
            lastPersistedAtMs = System.currentTimeMillis(),
            hasStoredWallet = true,
        )
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
                throw IllegalStateException(appContext.getString(R.string.stored_metadata_no_mnemonic))
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

    /** Reads only the non-secret restore height without decrypting the mnemonic. */
    private fun persistedRestoreHeightOrNull(): Long? = runCatching {
        val file = metadataFile()
        if (!file.exists()) return@runCatching null
        val json = JSONObject(file.readText())
        if (!json.has("restoreHeight")) return@runCatching null
        json.optLong("restoreHeight", 0L).takeIf { it >= 0L }
    }.getOrNull()

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
            _state.value = _state.value.copy(lastError = appContext.getString(R.string.failed_persist_settings_fmt, t.message ?: t.javaClass.simpleName))
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
            _state.value = _state.value.copy(lastError = appContext.getString(R.string.failed_persist_addrs_fmt, t.message ?: t.javaClass.simpleName))
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
