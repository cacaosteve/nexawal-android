package com.nexatrode.nexawal

import android.util.Log

import android.content.Context
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

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
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
 *     - metadata.json (mnemonic + restore height + network flag + node URL) — dev-only for now
 *     - <walletId>.cache — walletcore cache blob
 *
 * SECURITY NOTE:
 * - Storing the mnemonic in plaintext on disk is NOT production-safe.
 * - This is implemented as a bring-up step to mirror iOS flow quickly.
 * - In production, migrate mnemonic storage to Android Keystore-backed encryption.
 */
class WalletManager(
    private val appContext: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

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
        val lastError: String? = null,
        val version: String? = null,
        val syncStatus: WalletCore.SyncStatus? = null,
        val cacheInfo: CacheInfo? = null,

        // Wallet tab data
        val balance: WalletCore.Balance? = null,

        // Transfers
        val transfersJson: String? = null,
        val transfers: List<Transfer> = emptyList(),
        val transfersParseError: String? = null,

        val lastBalanceRefreshAtMs: Long? = null,
        val lastTransfersRefreshAtMs: Long? = null,

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
     * DEV NOTE:
     * - Mnemonic is stored in plaintext here to match iOS functionality quickly.
     * - Replace this with encrypted storage before production.
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

    private val transfersJsonParser: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /**
     * Default node URL (matches iOS default behavior).
     *
     * iOS defaultAddress: "10.0.2.2:18081"
     */
    fun defaultNodeUrl(): String = "http://10.0.2.2:18081"

    /**
     * Effective node URL to use for walletcore operations.
     *
     * If the user has edited Settings, state.nodeUrl will take precedence.
     */
    fun currentNodeUrl(): String = _state.value.nodeUrl ?: defaultNodeUrl()

    /**
     * Normalize user input into a URL string that walletcore expects.
     *
     * Accepts:
     * - "10.0.2.2:18081" -> "http://10.0.2.2:18081"
     * - "http://10.0.2.2:18081" (unchanged)
     * - "https://example.com:18081" (unchanged)
     *
     * Trims whitespace and rejects blank values.
     */
    private fun normalizeNodeUrl(raw: String): String {
        val s = raw.trim()
        require(s.isNotEmpty()) { "node URL must not be empty" }

        return if (s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)) {
            s
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
     * Returns true if a wallet is persisted on device (metadata exists).
     *
     * This mirrors iOS `WalletViewModel.hasStoredWallet()`.
     */
    suspend fun hasStoredWallet(): Boolean = withContext(ioDispatcher) {
        metadataFile().exists()
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
        val derivedAddress: String? = runCatching {
            WalletCore.derivePrimaryAddressFromMnemonic(
                mnemonic = mnemonic.trim(),
                mainnet = mainnet
            )
        }.getOrNull()

        _state.value = _state.value.copy(
            walletId = walletId,
            nodeUrl = normalizedNodeUrl,
            walletAddress = derivedAddress,
            lastError = null,
        )

        // Best-effort: import cache from disk if present.
        importCacheIfPresent(walletId)
        // Refresh status snapshot after open/import.
        updateStatusSnapshot(walletId)

        // Persist metadata for auto-load on next launch (dev-only plaintext storage).
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
        val nodeUrl = _state.value.nodeUrl ?: defaultNodeUrl()

        // Mirror iOS: log the node URL at refresh start so we can see exactly what the core is using.
        Log.i("WalletManager", "🌐 Refresh starting with nodeURL=$nodeUrl walletId=$walletId")

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
        runCatching { WalletCore.syncStatus(walletId) }
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
        runCatching { WalletCore.lastErrorMessage() }
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
                return _state.value.syncStatus ?: WalletCore.syncStatus(walletId)
            }
        }

        // Extra guard: if a refresh is currently marked in progress, short-circuit.
        // This protects against accidental double-starts due to UI recomposition or rapid taps.
        if (refreshInProgress.get()) {
            Log.i("WalletManager", "Refresh already in progress (flag); skipping new start walletId=$walletId")
            return _state.value.syncStatus ?: WalletCore.syncStatus(walletId)
        }

        refreshCancelRequested.set(false)
        refreshInProgress.set(true)
        _state.value = _state.value.copy(refreshInProgress = true, lastError = null)

        val job = scope.launch {
            try {
                withContext(ioDispatcher) {
                    // Mirror iOS: start async refresh in the core, then poll `syncStatus` until completion.
                    // This avoids blocking indefinitely inside a single JNI call (sync refresh can hang).
                    Log.i("WalletManager", "ASYNC_REFRESH_PATH_ACTIVE: calling wallet_refresh_async walletId=$walletId nodeUrl=$nodeUrl")
                    WalletCore.refreshAsync(walletId = walletId, nodeUrl = nodeUrl)
                }

                // Snapshot status right after refresh start.
                runCatching { WalletCore.syncStatus(walletId) }
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
                exportCacheAndPersist(walletId)

                _state.value = _state.value.copy(
                    refreshInProgress = false,
                    syncStatus = st,
                    lastError = null,
                )
            } catch (ce: CancellationException) {
                // Kotlin-side cancellation (we still request core cancel in cancelRefresh()).
                val st = runCatching { WalletCore.syncStatus(walletId) }.getOrNull()
                if (st != null) {
                    _state.value = _state.value.copy(syncStatus = st)
                }
                exportCacheAndPersist(walletId)
                _state.value = _state.value.copy(refreshInProgress = false)
            } catch (t: Throwable) {
                // Best-effort: persist progress even on failure.
                exportCacheAndPersist(walletId)
                val msg = t.message ?: t.javaClass.simpleName
                _state.value = _state.value.copy(refreshInProgress = false, lastError = msg)
            } finally {
                refreshInProgress.set(false)
            }
        }

        refreshJob = job
        job.join()

        return _state.value.syncStatus ?: WalletCore.syncStatus(walletId)
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

        runCatching {
            WalletCore.refreshCancel(walletId)
        }.onFailure { t ->
            // Don't block cancellation on this.
            _state.value = _state.value.copy(lastError = "refreshCancel failed: ${t.message ?: t.javaClass.simpleName}")
        }

        refreshJob?.cancel()
        exportCacheAndPersist(walletId)

        _state.value = _state.value.copy(refreshInProgress = false)
    }

    /**
     * Snapshot state for backgrounding. (Best-effort)
     *
     * This does NOT cancel refresh.
     */
    fun snapshotState() {
        val walletId = _state.value.walletId ?: return
        exportCacheAndPersist(walletId)
    }

    /**
     * Best-effort sync status query and store into state.
     */
    fun refreshStatusSnapshot() {
        val walletId = _state.value.walletId ?: return
        updateStatusSnapshot(walletId)
    }

    /**
     * Refresh and store balances in state (best-effort).
     *
     * This does NOT require a refresh; it reads whatever the core currently knows.
     */
    fun refreshBalanceSnapshot() {
        val walletId = _state.value.walletId ?: return
        val bal = runCatching { WalletCore.getBalance(walletId) }.getOrNull()
        if (bal != null) {
            _state.value = _state.value.copy(
                balance = bal,
                lastBalanceRefreshAtMs = System.currentTimeMillis(),
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
        val json = runCatching { WalletCore.listTransfersJson(walletId) }.getOrNull()
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
        refreshBalanceSnapshot()
        refreshTransfersSnapshot()
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
        val nodeUrl = _state.value.nodeUrl ?: defaultNodeUrl()

        return withContext(ioDispatcher) {
            val destinationsJson = SendJson.encodeDestinations(destinations)
            val raw = WalletCore.previewFeeJson(
                walletId = walletId,
                destinationsJson = destinationsJson,
                ringLen = ringLen,
                nodeUrl = nodeUrl,
            )
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
    ): SendJson.SendResult {
        val walletId = _state.value.walletId ?: throw IllegalStateException("No wallet open")
        val nodeUrl = _state.value.nodeUrl ?: defaultNodeUrl()

        val raw = withContext(ioDispatcher) {
            WalletCore.sendJson(
                walletId = walletId,
                toAddress = toAddress,
                amountPiconero = amountPiconero,
                ringLen = ringLen,
                nodeUrl = nodeUrl,
            )
        }
        val res = SendJson.decodeSendResult(raw)

        // Persist immediately so pending outgoing survives restart before next refresh.
        exportCacheAndPersist(walletId)

        _state.value = _state.value.copy(
            lastSendResult = res,
            lastError = null,
            lastSendOrSweepAtMs = System.currentTimeMillis(),
        )

        // Best-effort: refresh visible data snapshots (balance/transfers) after send.
        refreshWalletDataSnapshots()

        return res
    }

    /**
     * Preview "send max" (sweep).
     */
    suspend fun previewSweep(
        toAddress: String,
        ringLen: Int = 16,
    ): SendJson.SweepPreviewResult {
        val walletId = _state.value.walletId ?: throw IllegalStateException("No wallet open")
        val nodeUrl = _state.value.nodeUrl ?: defaultNodeUrl()

        return withContext(ioDispatcher) {
            val raw = WalletCore.previewSweepJson(
                walletId = walletId,
                toAddress = toAddress,
                ringLen = ringLen,
                nodeUrl = nodeUrl,
            )
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
    ): SendJson.SweepSendResult {
        val walletId = _state.value.walletId ?: throw IllegalStateException("No wallet open")
        val nodeUrl = _state.value.nodeUrl ?: defaultNodeUrl()

        val raw = withContext(ioDispatcher) {
            WalletCore.sweepJson(
                walletId = walletId,
                toAddress = toAddress,
                ringLen = ringLen,
                nodeUrl = nodeUrl,
            )
        }
        val res = SendJson.decodeSweepSendResult(raw)

        // Persist immediately so pending outgoing survives restart before next refresh.
        exportCacheAndPersist(walletId)

        _state.value = _state.value.copy(
            lastSweepSendResult = res,
            lastError = null,
            lastSendOrSweepAtMs = System.currentTimeMillis(),
        )

        // Best-effort: refresh visible data snapshots (balance/transfers) after sweep.
        refreshWalletDataSnapshots()

        return res
    }

    private fun parseTransfersJson(json: String): Pair<List<Transfer>, String?> {
        return try {
            val parsed = transfersJsonParser.decodeFromString<List<Transfer>>(json)
            parsed to null
        } catch (t: Throwable) {
            emptyList<Transfer>() to (t.message ?: t.javaClass.simpleName)
        }
    }

    private fun updateStatusSnapshot(walletId: String) {
        val st = runCatching { WalletCore.syncStatus(walletId) }.getOrNull()
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

        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()

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
        pollIntervalMs: Long = 200L,
        stallTimeoutMs: Long = 45_000L,
    ): WalletCore.SyncStatus = withContext(ioDispatcher) {
        var targetHeight: Long? = null
        var lastScannedSnapshot: Long = 0
        var lastProgressAtMs = System.currentTimeMillis()

        // Periodic persistence while refresh is running.
        var lastPersistAtMs = 0L
        val persistIntervalMs = 15_000L

        // Mirror iOS: sample core error state periodically even if progress is happening.
        var lastCoreErrSampleAtMs = 0L
        val coreErrSampleIntervalMs = 1_000L

        // Mirror iOS: log progress periodically (and push syncStatus into state so UI updates continuously).
        var lastProgressLogAtMs = 0L
        val progressLogIntervalMs = 1_000L
        var lastRateSampleAtMs = System.currentTimeMillis()
        var lastRateSampleScanned = 0L

        while (isActive) {
            ensureActive()

            if (refreshCancelRequested.get()) {
                exportCacheAndPersist(walletId)
                throw CancellationException("refresh cancelled")
            }

            val st = WalletCore.syncStatus(walletId)

            // Push status into state continuously so Compose can render progress.
            _state.value = _state.value.copy(syncStatus = st)

            // Capture the initial target chain height once (so we don't chase a moving tip).
            // Avoid locking onto restoreHeight as the target (which reads as chainHeight initially).
            if (targetHeight == null && st.chainHeight > st.restoreHeight) {
                targetHeight = st.chainHeight
                Log.i("WalletManager", "Refresh target height set to $targetHeight (restoreHeight=${st.restoreHeight})")
            }

            // Mirror iOS: sample core error state even if progress continues.
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastCoreErrSampleAtMs >= coreErrSampleIntervalMs) {
                lastCoreErrSampleAtMs = nowMs
                val coreErr = runCatching { WalletCore.lastErrorMessage() }.getOrNull()
                if (!coreErr.isNullOrBlank()) {
                    Log.w("WalletManager", "Core error sample during refresh: $coreErr")
                }
            }

            // Mirror iOS: periodic progress logging (blocks remaining + rough blocks/sec).
            if (nowMs - lastProgressLogAtMs >= progressLogIntervalMs) {
                lastProgressLogAtMs = nowMs

                val tip = targetHeight ?: st.chainHeight
                val remaining = if (tip > 0) maxOf(0L, tip - st.lastScanned) else -1L

                val dtMs = maxOf(1L, nowMs - lastRateSampleAtMs)
                val dScanned = (st.lastScanned - lastRateSampleScanned).coerceAtLeast(0L)
                val blocksPerSec = (dScanned.toDouble() * 1000.0) / dtMs.toDouble()

                Log.i(
                    "WalletManager",
                    "⏳ Refresh progress: scanned=${st.lastScanned}, restore=${st.restoreHeight}, chain=${st.chainHeight}, target=${targetHeight ?: 0L}, remaining=$remaining, rate=${"%.1f".format(blocksPerSec)} blks/s"
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
            } else if (targetHeight != null && lastScannedSnapshot == 0L) {
                // Defensive: if we started with lastScanned=0 and target gets set, reset the timer so we
                // don't report a stall before any blocks are scanned.
                lastProgressAtMs = nowMs
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

            if (tip != null && remaining != null && remaining > 0 && (nowMs - lastProgressAtMs > stallTimeoutMs)) {
                val coreErr = runCatching { WalletCore.lastErrorMessage() }.getOrNull()

                Log.e(
                    "WalletManager",
                    "STALL: refresh appears stuck (>${stallTimeoutMs}ms) walletId=$walletId " +
                        "lastScanned=${st.lastScanned} restoreHeight=${st.restoreHeight} chainHeight=${st.chainHeight} " +
                        "target=$tip remaining=$remaining lastError=${coreErr ?: "<null>"}"
                )

                if (!coreErr.isNullOrBlank()) {
                    Log.w("WalletManager", "Core lastErrorMessage (stall): $coreErr")
                }

                exportCacheAndPersist(walletId)
                throw IOException(
                    "Refresh stalled (>${stallTimeoutMs}ms) lastScanned=${st.lastScanned} chainHeight=${st.chainHeight}" +
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
    }

    private fun importCacheIfPresent(walletId: String) {
        val f = cacheFile(walletId, mainnet = true)
        if (!f.exists()) return

        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return
        if (bytes.isEmpty()) return

        runCatching {
            WalletCore.importCache(walletId, bytes)
        }.onFailure { t ->
            // Best-effort only: don't fail opening wallet due to bad cache.
            _state.value = _state.value.copy(lastError = "cache import failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun exportCacheAndPersist(walletId: String) {
        val cache = runCatching { WalletCore.exportCache(walletId) }.getOrNull() ?: return

        val f = cacheFile(walletId, mainnet = true)
        runCatching {
            ensureCacheDirExists(f)
            f.writeBytes(cache)

            _state.value = _state.value.copy(
                cacheInfo = CacheInfo(
                    filePath = f.absolutePath,
                    bytesOnDisk = f.length(),
                    lastSavedAtMs = System.currentTimeMillis(),
                )
            )
        }.onFailure { t ->
            _state.value = _state.value.copy(lastError = "cache export failed: ${t.message ?: t.javaClass.simpleName}")
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

        // Derive and cache primary address for UI.
        val derivedAddress: String? = runCatching {
            WalletCore.derivePrimaryAddressFromMnemonic(
                mnemonic = meta.mnemonic,
                mainnet = meta.mainnet
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

        // Import cache best-effort, then snapshot status.
        importCacheIfPresent(meta.walletId)
        updateStatusSnapshot(meta.walletId)
        refreshWalletDataSnapshots()

        // iOS parity: after loading a stored wallet, immediately refresh.
        // This will persist cache periodically during refresh and at completion.
        runCatching { refreshWallet() }.onFailure { t ->
            _state.value = _state.value.copy(lastError = t.message ?: t.javaClass.simpleName)
        }
        refreshWalletDataSnapshots()

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
            // Very small, stable JSON blob; build manually to avoid extra plugin requirements.
            val json = """
                {
                  "walletId": "${meta.walletId}",
                  "mnemonic": "${meta.mnemonic.replace("\"", "\\\"")}",
                  "restoreHeight": ${meta.restoreHeight},
                  "mainnet": ${meta.mainnet},
                  "nodeUrl": "${meta.nodeUrl.replace("\"", "\\\"")}",
                  "savedAtMs": ${meta.savedAtMs}
                }
            """.trimIndent()
            f.writeText(json)
            _state.value = _state.value.copy(lastPersistedAtMs = System.currentTimeMillis(), hasStoredWallet = true)
        }.onFailure { t ->
            _state.value = _state.value.copy(lastError = "Failed to persist metadata: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun readMetadata(): StoredWalletMetadata {
        val f = metadataFile()
        val raw = f.readText()

        // Minimal JSON extraction (no dependency on Android JSON libs here).
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
        fun findBool(key: String): Boolean {
            val needle = "\"$key\""
            val idx = raw.indexOf(needle)
            if (idx < 0) throw IllegalStateException("Missing key: $key")
            val colon = raw.indexOf(':', idx)
            val end = raw.indexOfAny(charArrayOf(',', '\n', '\r', '}'), startIndex = colon + 1).let { if (it < 0) raw.length else it }
            return raw.substring(colon + 1, end).trim().toBoolean()
        }

        return StoredWalletMetadata(
            walletId = runCatching { findString("walletId") }.getOrElse { DEFAULT_WALLET_ID },
            mnemonic = findString("mnemonic"),
            restoreHeight = runCatching { findLong("restoreHeight") }.getOrElse { 0L },
            mainnet = runCatching { findBool("mainnet") }.getOrElse { true },
            nodeUrl = runCatching { findString("nodeUrl") }.getOrElse { defaultNodeUrl() },
            savedAtMs = runCatching { findLong("savedAtMs") }.getOrElse { 0L },
        )
    }

    private fun metadataFile(): File {
        val dir = File(appContext.filesDir, "WalletSlot")
        return File(dir, "metadata.json")
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
            nodeUrl = findString("nodeUrl"),
            savedAtMs = runCatching { findLong("savedAtMs") }.getOrElse { 0L },
        )
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
