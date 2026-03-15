package com.nexatrode.nexawal.walletcore

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Kotlin-only API surface for the Monero wallet core.
 *
 * IMPORTANT:
 * This module (`:walletcore-api`) is a Kotlin/JVM module, not an Android module.
 * That means:
 * - It cannot itself package native libraries.
 * - It should not reference Android framework types (e.g. Context).
 *
 * Runtime expectation:
 * - The consuming Android app must also include the `:walletcore` Android library module (or otherwise package):
 *   - `libmonerowalletcore.so`
 *   - `libwalletcore_jni.so`
 *   - and dependencies like `libc++_shared.so`
 *
 * Then, the app can call this API and it will load the native JNI shim via `System.loadLibrary(...)`.
 *
 * NOTE:
 * We intentionally keep this module free of JSON serialization plugins and dependencies.
 * Higher-level JSON parsing/encoding for sends and transfers lives in the Android app module.
 */
object WalletCore {

    // Monero atomic units: 1 XMR = 1e12 piconero
    private const val PICONERO_PER_XMR: Long = 1_000_000_000_000L

    /**
     * Returns the wallet core version string.
     *
     * Calls through JNI into `walletcore_version()` from the C ABI.
     */
    @JvmStatic
    fun version(): String = WalletCoreJni.version()

    /**
     * Generate a new English Monero mnemonic (25 words).
     *
     * Mirrors the C ABI:
     *   int32_t wallet_generate_mnemonic_english(...)
     */
    @JvmStatic
    fun generateMnemonicEnglish(): String = WalletCoreJni.generateMnemonicEnglish()

    /**
     * Returns the last error message recorded by the core, if any.
     *
     * Mirrors the C ABI:
     *   char* walletcore_last_error_message(void)
     *
     * Notes:
     * - This is best-effort diagnostic state; it may be cleared on subsequent successful calls.
     * - Returns null if there is no recorded error.
     */
    @JvmStatic
    fun lastErrorMessage(): String? = WalletCoreJni.lastErrorMessage()

    /**
     * Derive the primary address (account 0, subaddress 0) from a 25-word mnemonic.
     *
     * Mirrors the C ABI:
     *   int32_t wallet_primary_address_from_mnemonic(
     *     const char* mnemonic,
     *     uint8_t is_mainnet,
     *     char* out_buf,
     *     size_t out_buf_len,
     *     size_t* out_written
     *   )
     *
     * Throws a RuntimeException on native error (via JNI).
     */
    @JvmStatic
    fun derivePrimaryAddressFromMnemonic(
        mnemonic: String,
        mainnet: Boolean = true,
    ): String {
        require(mnemonic.isNotBlank()) { "mnemonic must not be blank" }
        return WalletCoreJni.primaryAddressFromMnemonic(mnemonic.trim(), mainnet)
    }

    /**
     * Derive a subaddress from a 25-word mnemonic.
     *
     * Mirrors the C ABI:
     *   int32_t wallet_derive_subaddress_from_mnemonic(...)
     */
    @JvmStatic
    fun deriveSubaddressFromMnemonic(
        mnemonic: String,
        accountIndex: Int = 0,
        subaddressIndex: Int,
        mainnet: Boolean = true,
    ): String {
        require(mnemonic.isNotBlank()) { "mnemonic must not be blank" }
        require(accountIndex >= 0) { "accountIndex must be >= 0" }
        require(subaddressIndex >= 0) { "subaddressIndex must be >= 0" }
        return WalletCoreJni.subaddressFromMnemonic(
            mnemonic.trim(),
            accountIndex,
            subaddressIndex,
            mainnet
        )
    }

    /**
     * Format a piconero amount as a decimal XMR string (12 fractional digits).
     *
     * Example:
     * - 0 -> "0.000000000000"
     * - 1_000_000_000_000 -> "1.000000000000"
     */
    @JvmStatic
    fun formatXmr(piconero: Long): String {
        val bd = BigDecimal(piconero).divide(BigDecimal(PICONERO_PER_XMR), 12, RoundingMode.DOWN)
        return bd.toPlainString()
    }

    /**
     * Open/register a wallet from a 25-word mnemonic.
     *
     * Mirrors the C ABI:
     *   int32_t wallet_open_from_mnemonic(
     *     const char* wallet_id,
     *     const char* mnemonic,
     *     uint64_t restore_height,
     *     uint8_t is_mainnet
     *   )
     */
    @JvmStatic
    fun openFromMnemonic(
        walletId: String,
        mnemonic: String,
        restoreHeight: Long = 0L,
        mainnet: Boolean = true,
    ) {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        require(mnemonic.isNotBlank()) { "mnemonic must not be blank" }
        WalletCoreJni.openFromMnemonic(walletId, mnemonic, restoreHeight, mainnet)
    }

    /**
     * Start a background refresh against the given daemon URL.
     *
     * Mirrors the C ABI:
     *   int32_t wallet_refresh_async(const char* wallet_id, const char* node_url)
     */
    @JvmStatic
    fun refreshAsync(walletId: String, nodeUrl: String? = null) {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        WalletCoreJni.refreshAsync(walletId, nodeUrl)
    }

    /**
     * Set the registered subaddress gap limit for scanning (minimum 1).
     *
     * Mirrors the C ABI:
     *   int32_t wallet_set_gap_limit(const char* wallet_id, uint32_t gap_limit)
     */
    @JvmStatic
    fun setGapLimit(walletId: String, gapLimit: Int) {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        require(gapLimit >= 1) { "gapLimit must be >= 1" }
        WalletCoreJni.setGapLimit(walletId, gapLimit)
    }

    /**
     * Best-effort: set an environment variable in the native process.
     *
     * This is used for iOS parity with `MoneroConfig.scanNodeURL()`, which sets:
     * - WALLETCORE_ACCOUNT_GAP
     */
    @JvmStatic
    fun setEnv(key: String, value: String) {
        require(key.isNotBlank()) { "key must not be blank" }
        WalletCoreJni.setEnv(key.trim(), value)
    }

    /**
     * Convenience helper for iOS parity: set account lookahead.
     *
     * The core reads this in refresh via:
     *   WALLETCORE_ACCOUNT_GAP
     */
    @JvmStatic
    fun setAccountGap(accountGap: Int) {
        require(accountGap >= 1) { "accountGap must be >= 1" }
        setEnv("WALLETCORE_ACCOUNT_GAP", accountGap.toString())
    }

    /**
     * Perform a synchronous refresh against the given daemon URL.
     *
     * This is primarily useful for debugging on Android when the async worker
     * (`refreshAsync`) appears not to update status.
     *
     * Mirrors the C ABI:
     *   int32_t wallet_refresh(const char* wallet_id, const char* node_url, uint64_t* out_last_scanned)
     *
     * Returns:
     * - The last scanned height reported by the refresh call.
     */
    @JvmStatic
    fun refresh(walletId: String, nodeUrl: String? = null): Long {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        return WalletCoreJni.refresh(walletId, nodeUrl)
    }

    /**
     * Request cancellation of an in-flight refresh.
     *
     * Mirrors the C ABI:
     *   int32_t wallet_refresh_cancel(const char* wallet_id)
     */
    @JvmStatic
    fun refreshCancel(walletId: String) {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        WalletCoreJni.refreshCancel(walletId)
    }

    /**
     * Retrieve current sync status snapshot for a wallet.
     *
     * Mirrors the C ABI:
     *   int32_t wallet_sync_status(...)
     */
    @JvmStatic
    fun syncStatus(walletId: String): SyncStatus {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        val a = WalletCoreJni.syncStatus(walletId)
        require(a.size == 5) { "syncStatus returned unexpected array size: ${a.size}" }
        return SyncStatus(
            chainHeight = a[0],
            chainTime = a[1],
            lastRefreshTimestamp = a[2],
            lastScanned = a[3],
            restoreHeight = a[4],
        )
    }

    data class SyncStatus(
        val chainHeight: Long,
        val chainTime: Long,
        val lastRefreshTimestamp: Long,
        val lastScanned: Long,
        val restoreHeight: Long,
    )

    /**
     * Get balances (piconero) for the wallet.
     *
     * Mirrors the C ABI:
     *   int32_t wallet_get_balance(const char* wallet_id, uint64_t* out_total_piconero, uint64_t* out_unlocked_piconero)
     */
    @JvmStatic
    fun getBalance(walletId: String): Balance {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        val a = WalletCoreJni.getBalance(walletId)
        require(a.size == 2) { "getBalance returned unexpected array size: ${a.size}" }
        return Balance(
            totalPiconero = a[0],
            unlockedPiconero = a[1],
        )
    }

    /**
     * Get balances (piconero) for the wallet constrained by an optional input filter.
     *
     * Mirrors the C ABI:
     *   int32_t wallet_get_balance_with_filter(...)
     */
    @JvmStatic
    fun getBalanceWithFilter(walletId: String, filterJson: String? = null): Balance {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        val a = WalletCoreJni.getBalanceWithFilter(walletId, filterJson)
        require(a.size == 2) { "getBalanceWithFilter returned unexpected array size: ${a.size}" }
        return Balance(
            totalPiconero = a[0],
            unlockedPiconero = a[1],
        )
    }

    data class Balance(
        val totalPiconero: Long,
        val unlockedPiconero: Long,
    ) {
        val totalXmr: String get() = formatXmr(totalPiconero)
        val unlockedXmr: String get() = formatXmr(unlockedPiconero)
    }

    /**
     * List transfers (history) as raw JSON.
     *
     * Mirrors the C ABI:
     *   char* wallet_list_transfers_json(const char* wallet_id)
     */
    @JvmStatic
    fun listTransfersJson(walletId: String): String {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        return WalletCoreJni.listTransfersJson(walletId)
    }

    /**
     * Export observed outputs as a JSON string.
     *
     * Mirrors the C ABI:
     *   char* wallet_export_outputs_json(const char* wallet_id)
     *
     * This is useful for "Receive" / "incoming detection" and debugging.
     */
    @JvmStatic
    fun exportOutputsJson(walletId: String): String {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        return WalletCoreJni.exportOutputsJson(walletId)
    }

    /**
     * Import a previously-exported cache blob for the given walletId.
     *
     * Mirrors the C ABI:
     *   int32_t wallet_import_cache(const char* wallet_id, const uint8_t* cache_ptr, size_t cache_len)
     */
    @JvmStatic
    fun importCache(walletId: String, cache: ByteArray) {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        require(cache.isNotEmpty()) { "cache must not be empty" }
        WalletCoreJni.importCache(walletId, cache)
    }

    /**
     * Export the current cache blob for the given walletId.
     *
     * Mirrors the C ABI (two-phase buffer API under the hood):
     *   int32_t wallet_export_cache(const char* wallet_id, uint8_t* out_buf, size_t out_buf_len, size_t* out_written)
     *
     * Returns null if no cache is available yet.
     */
    @JvmStatic
    fun exportCache(walletId: String): ByteArray? {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        return WalletCoreJni.exportCache(walletId)
    }

    // ===== Send / Fee preview / Sweep (Send Max) =====
    //
    // We intentionally only expose the raw JSON-returning JNI calls here.
    // JSON encoding/decoding models live in the Android app module.

    @JvmStatic
    fun previewFeeJson(
        walletId: String,
        destinationsJson: String,
        ringLen: Int = 16,
        nodeUrl: String? = null,
    ): String {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        require(destinationsJson.isNotBlank()) { "destinationsJson must not be blank" }
        require(ringLen in 1..255) { "ringLen must be 1..255" }
        return WalletCoreJni.previewFee(walletId, nodeUrl, destinationsJson, ringLen)
    }

    @JvmStatic
    fun previewFeeJsonWithFilter(
        walletId: String,
        destinationsJson: String,
        filterJson: String? = null,
        ringLen: Int = 16,
        nodeUrl: String? = null,
    ): String {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        require(destinationsJson.isNotBlank()) { "destinationsJson must not be blank" }
        require(ringLen in 1..255) { "ringLen must be 1..255" }
        return WalletCoreJni.previewFeeWithFilter(walletId, nodeUrl, destinationsJson, filterJson, ringLen)
    }

    @JvmStatic
    fun sendJson(
        walletId: String,
        toAddress: String,
        amountPiconero: Long,
        ringLen: Int = 16,
        nodeUrl: String? = null,
    ): String {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        require(toAddress.isNotBlank()) { "toAddress must not be blank" }
        require(amountPiconero > 0) { "amountPiconero must be > 0" }
        require(ringLen in 1..255) { "ringLen must be 1..255" }
        return WalletCoreJni.send(walletId, nodeUrl, toAddress, amountPiconero, ringLen)
    }

    @JvmStatic
    fun sendJsonWithFilter(
        walletId: String,
        destinationsJson: String,
        filterJson: String? = null,
        ringLen: Int = 16,
        nodeUrl: String? = null,
    ): String {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        require(destinationsJson.isNotBlank()) { "destinationsJson must not be blank" }
        require(ringLen in 1..255) { "ringLen must be 1..255" }
        return WalletCoreJni.sendWithFilter(walletId, nodeUrl, destinationsJson, filterJson, ringLen)
    }

    @JvmStatic
    fun previewSweepJson(
        walletId: String,
        toAddress: String,
        ringLen: Int = 16,
        nodeUrl: String? = null,
    ): String {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        require(toAddress.isNotBlank()) { "toAddress must not be blank" }
        require(ringLen in 1..255) { "ringLen must be 1..255" }
        return WalletCoreJni.previewSweep(walletId, nodeUrl, toAddress, ringLen)
    }

    @JvmStatic
    fun previewSweepJsonWithFilter(
        walletId: String,
        toAddress: String,
        filterJson: String? = null,
        ringLen: Int = 16,
        nodeUrl: String? = null,
    ): String {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        require(toAddress.isNotBlank()) { "toAddress must not be blank" }
        require(ringLen in 1..255) { "ringLen must be 1..255" }
        return WalletCoreJni.previewSweepWithFilter(walletId, nodeUrl, toAddress, filterJson, ringLen)
    }

    @JvmStatic
    fun sweepJson(
        walletId: String,
        toAddress: String,
        ringLen: Int = 16,
        nodeUrl: String? = null,
    ): String {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        require(toAddress.isNotBlank()) { "toAddress must not be blank" }
        require(ringLen in 1..255) { "ringLen must be 1..255" }
        return WalletCoreJni.sweep(walletId, nodeUrl, toAddress, ringLen)
    }

    @JvmStatic
    fun sweepJsonWithFilter(
        walletId: String,
        toAddress: String,
        filterJson: String? = null,
        ringLen: Int = 16,
        nodeUrl: String? = null,
    ): String {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        require(toAddress.isNotBlank()) { "toAddress must not be blank" }
        require(ringLen in 1..255) { "ringLen must be 1..255" }
        return WalletCoreJni.sweepWithFilter(walletId, nodeUrl, toAddress, filterJson, ringLen)
    }

    @JvmStatic
    fun forceRescanFromHeight(walletId: String, fromHeight: Long) {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        require(fromHeight >= 0L) { "fromHeight must be >= 0" }
        WalletCoreJni.forceRescanFromHeight(walletId, fromHeight)
    }

    @JvmStatic
    fun resetTrackedOutputs(walletId: String) {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        WalletCoreJni.resetTrackedOutputs(walletId)
    }

    @JvmStatic
    fun startZmqListener(endpoint: String) {
        require(endpoint.isNotBlank()) { "endpoint must not be blank" }
        WalletCoreJni.startZmqListener(endpoint)
    }

    @JvmStatic
    fun stopZmqListener() {
        WalletCoreJni.stopZmqListener()
    }

    @JvmStatic
    fun zmqSequence(): Long = WalletCoreJni.zmqSequence()
}

/**
 * JNI declarations for the C++ bridge.
 *
 * Library loading:
 * - We explicitly load `monerowalletcore` first, then `walletcore_jni`.
 *   This avoids dlopen dependency resolution issues on some Android configurations.
 */
internal object WalletCoreJni {

    init {
        System.loadLibrary("monerowalletcore")
        System.loadLibrary("walletcore_jni")
    }

    external fun version(): String
    external fun generateMnemonicEnglish(): String

    /**
     * Returns the last error message recorded by the core, if any.
     *
     * Mirrors the C ABI:
     *   char* walletcore_last_error_message(void)
     *
     * Returns:
     * - null if no error message is currently recorded
     * - a non-empty string otherwise
     */
    external fun lastErrorMessage(): String?

    external fun primaryAddressFromMnemonic(mnemonic: String, mainnet: Boolean): String
    external fun subaddressFromMnemonic(
        mnemonic: String,
        accountIndex: Int,
        subaddressIndex: Int,
        mainnet: Boolean
    ): String

    external fun openFromMnemonic(walletId: String, mnemonic: String, restoreHeight: Long, mainnet: Boolean)

    external fun refreshAsync(walletId: String, nodeUrl: String?)

    /**
     * Synchronous refresh.
     *
     * Returns: lastScanned (u64) as a signed Long (jlong). Heights fit in signed 64-bit.
     */
    external fun refresh(walletId: String, nodeUrl: String?): Long

    external fun refreshCancel(walletId: String)

    /**
     * Returns: long[5] = [chainHeight, chainTime, lastRefreshTimestamp, lastScanned, restoreHeight]
     */
    external fun syncStatus(walletId: String): LongArray

    external fun importCache(walletId: String, cache: ByteArray)

    external fun exportCache(walletId: String): ByteArray?

    /**
     * Returns: long[2] = [total_piconero, unlocked_piconero]
     */
    external fun getBalance(walletId: String): LongArray
    external fun getBalanceWithFilter(walletId: String, filterJson: String?): LongArray

    external fun listTransfersJson(walletId: String): String

    external fun exportOutputsJson(walletId: String): String

    // ===== Scan / tuning knobs (iOS parity) =====

    /**
     * Set the subaddress gap limit used by scanning.
     *
     * Mirrors the C ABI:
     *   int32_t wallet_set_gap_limit(const char* wallet_id, uint32_t gap_limit)
     */
    external fun setGapLimit(walletId: String, gapLimit: Int)

    /**
     * Set an environment variable in the native process.
     *
     * Used for iOS parity for settings like:
     * - WALLETCORE_ACCOUNT_GAP (account lookahead)
     */
    external fun setEnv(key: String, value: String)

    // ===== Send / Fee preview / Sweep (Send Max) =====

    external fun previewFee(walletId: String, nodeUrl: String?, destinationsJson: String, ringLen: Int): String
    external fun previewFeeWithFilter(
        walletId: String,
        nodeUrl: String?,
        destinationsJson: String,
        filterJson: String?,
        ringLen: Int
    ): String

    external fun send(walletId: String, nodeUrl: String?, toAddress: String, amountPiconero: Long, ringLen: Int): String
    external fun sendWithFilter(
        walletId: String,
        nodeUrl: String?,
        destinationsJson: String,
        filterJson: String?,
        ringLen: Int
    ): String

    external fun previewSweep(walletId: String, nodeUrl: String?, toAddress: String, ringLen: Int): String
    external fun previewSweepWithFilter(
        walletId: String,
        nodeUrl: String?,
        toAddress: String,
        filterJson: String?,
        ringLen: Int
    ): String

    external fun sweep(walletId: String, nodeUrl: String?, toAddress: String, ringLen: Int): String
    external fun sweepWithFilter(
        walletId: String,
        nodeUrl: String?,
        toAddress: String,
        filterJson: String?,
        ringLen: Int
    ): String

    external fun forceRescanFromHeight(walletId: String, fromHeight: Long)
    external fun resetTrackedOutputs(walletId: String)
    external fun startZmqListener(endpoint: String)
    external fun stopZmqListener()
    external fun zmqSequence(): Long
}
