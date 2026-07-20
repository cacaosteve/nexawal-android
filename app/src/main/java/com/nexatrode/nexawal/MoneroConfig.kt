package com.nexatrode.nexawal

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.max
import kotlin.math.min

/**
 * MoneroConfig (Android)
 *
 * Android equivalent of iOS `MoneroConfig.swift` for the bring-up settings we care about right now:
 * - gap limit (subaddress minor lookahead within each account)
 * - account gap (account lookahead, i.e. number of major indices to scan starting from 0)
 * - basic network policy metadata (clearnet / i2p / hybrid) for UI parity
 *
 * Goals:
 * - Persist user overrides across app restarts (SharedPreferences)
 * - Provide wallet2-like defaults (match iOS defaults)
 * - Clamp values to safe ranges
 *
 * Notes:
 * - This file intentionally does NOT talk to walletcore/JNI directly.
 *   WalletManager (or the Settings UI) should:
 *   - read these values and apply them to walletcore (gap limit via API, account gap via env var)
 * - Network policy is currently UI-facing metadata only unless another caller explicitly uses it.
 */
object MoneroConfig {

    // SharedPreferences file name (scoped to app).
    private const val PREFS_NAME: String = "monero_config"

    // Keys (match iOS naming semantics where possible).
    private const val KEY_GAP_LIMIT: String = "monero_gap_limit"
    private const val KEY_ACCOUNT_GAP: String = "walletcore_account_gap"
    private const val KEY_REQUIRE_DEVICE_AUTH: String = "wallet_require_device_auth"
    private const val KEY_NETWORK_POLICY: String = "monero_network_policy"
    private const val KEY_I2P_RPC_ADDRESS: String = "monero_i2p_rpc_address"
    private const val KEY_I2P_HTTP_PROXY: String = "monero_i2p_http_proxy"
    private const val KEY_CLASSIC_UI: String = "ui_classic_mode"

    // Defaults (match iOS MoneroConfig.swift).
    const val DEFAULT_GAP_LIMIT: Int = 50
    const val DEFAULT_ACCOUNT_GAP: Int = 1
    const val DEFAULT_REQUIRE_DEVICE_AUTH: Boolean = false
    /** Classic UI ON = standard non-neon look; OFF (default) = neon terminal theme. */
    const val DEFAULT_CLASSIC_UI: Boolean = false
    private const val DEFAULT_NETWORK_POLICY_RAW: String = "clearnet"
    private const val DEFAULT_I2P_RPC_ADDRESS: String =
        "cvxtgqjorfif6i5x5fenys6fj7hzddbgavpyutps6gphywnlklqa.b32.i2p:18081"

    // Safety clamps.
    private const val GAP_LIMIT_MIN: Int = 1
    private const val GAP_LIMIT_MAX: Int = 100_000
    private const val ACCOUNT_GAP_MIN: Int = 1
    private const val ACCOUNT_GAP_MAX: Int = 1_000

    private fun prefs(context: Context): SharedPreferences {
        // Use applicationContext to avoid leaking Activities.
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    enum class NetworkPolicy(val raw: String) {
        CLEARNET("clearnet"),
        I2P("i2p"),
        HYBRID("hybrid");

        companion object {
            fun fromRaw(raw: String?): NetworkPolicy =
                entries.firstOrNull { it.raw == raw } ?: CLEARNET
        }
    }

    /**
     * Read the persisted gap limit, falling back to default.
     *
     * Semantics:
     * - Represents the subaddress minor lookahead for each scanned account.
     * - Clamped to [1, 100_000].
     */
    @JvmStatic
    fun gapLimit(context: Context): Int {
        val raw = prefs(context).getInt(KEY_GAP_LIMIT, 0)
        val value = if (raw > 0) raw else DEFAULT_GAP_LIMIT
        return clamp(value, GAP_LIMIT_MIN, GAP_LIMIT_MAX)
    }

    /**
     * Persist a new gap limit.
     *
     * Input is clamped to [1, 100_000].
     */
    @JvmStatic
    fun setGapLimit(context: Context, gapLimit: Int) {
        val clamped = clamp(gapLimit, GAP_LIMIT_MIN, GAP_LIMIT_MAX)
        prefs(context).edit().putInt(KEY_GAP_LIMIT, clamped).apply()
    }

    /**
     * Read the persisted account gap (account lookahead), falling back to default.
     *
     * Semantics:
     * - Number of major indices to scan, starting from 0 (i.e. scan majors [0..accountGap)).
     * - Clamped to [1, 1_000].
     */
    @JvmStatic
    fun accountGap(context: Context): Int {
        val raw = prefs(context).getInt(KEY_ACCOUNT_GAP, 0)
        val value = if (raw > 0) raw else DEFAULT_ACCOUNT_GAP
        return clamp(value, ACCOUNT_GAP_MIN, ACCOUNT_GAP_MAX)
    }

    /**
     * Persist a new account gap (account lookahead).
     *
     * Input is clamped to [1, 1_000].
     */
    @JvmStatic
    fun setAccountGap(context: Context, accountGap: Int) {
        val clamped = clamp(accountGap, ACCOUNT_GAP_MIN, ACCOUNT_GAP_MAX)
        prefs(context).edit().putInt(KEY_ACCOUNT_GAP, clamped).apply()
    }

    @JvmStatic
    fun requireDeviceAuth(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_REQUIRE_DEVICE_AUTH, DEFAULT_REQUIRE_DEVICE_AUTH)
    }

    @JvmStatic
    fun setRequireDeviceAuth(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_REQUIRE_DEVICE_AUTH, enabled).apply()
    }

    @JvmStatic
    fun isClassicUIEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CLASSIC_UI, DEFAULT_CLASSIC_UI)
    }

    @JvmStatic
    fun setClassicUIEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CLASSIC_UI, enabled).apply()
    }

    @JvmStatic
    fun networkPolicy(context: Context): NetworkPolicy {
        val raw = prefs(context).getString(KEY_NETWORK_POLICY, DEFAULT_NETWORK_POLICY_RAW)
        return NetworkPolicy.fromRaw(raw)
    }

    @JvmStatic
    fun setNetworkPolicy(context: Context, policy: NetworkPolicy) {
        prefs(context).edit().putString(KEY_NETWORK_POLICY, policy.raw).apply()
    }

    @JvmStatic
    fun i2pRpcAddress(context: Context): String {
        return prefs(context).getString(KEY_I2P_RPC_ADDRESS, DEFAULT_I2P_RPC_ADDRESS)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_I2P_RPC_ADDRESS
    }

    @JvmStatic
    fun setI2pRpcAddress(context: Context, address: String?) {
        val edit = prefs(context).edit()
        if (address.isNullOrBlank()) {
            edit.remove(KEY_I2P_RPC_ADDRESS)
        } else {
            edit.putString(KEY_I2P_RPC_ADDRESS, address.trim())
        }
        edit.apply()
    }

    @JvmStatic
    fun i2pHttpProxyAddress(context: Context): String? {
        return prefs(context).getString(KEY_I2P_HTTP_PROXY, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    @JvmStatic
    fun setI2pHttpProxyAddress(context: Context, address: String?) {
        val edit = prefs(context).edit()
        if (address.isNullOrBlank()) {
            edit.remove(KEY_I2P_HTTP_PROXY)
        } else {
            edit.putString(KEY_I2P_HTTP_PROXY, address.trim())
        }
        edit.apply()
    }

    @JvmStatic
    fun broadcastNodeUrl(context: Context, currentNodeUrl: String): String {
        return when (networkPolicy(context)) {
            NetworkPolicy.CLEARNET -> currentNodeUrl
            NetworkPolicy.I2P, NetworkPolicy.HYBRID -> normalizeUrl(i2pRpcAddress(context))
        }
    }

    /**
     * Reset both values back to defaults (useful for debugging).
     */
    @JvmStatic
    fun resetToDefaults(context: Context) {
        prefs(context).edit()
            .putInt(KEY_GAP_LIMIT, DEFAULT_GAP_LIMIT)
            .putInt(KEY_ACCOUNT_GAP, DEFAULT_ACCOUNT_GAP)
            .putBoolean(KEY_REQUIRE_DEVICE_AUTH, DEFAULT_REQUIRE_DEVICE_AUTH)
            .putBoolean(KEY_CLASSIC_UI, DEFAULT_CLASSIC_UI)
            .putString(KEY_NETWORK_POLICY, DEFAULT_NETWORK_POLICY_RAW)
            .remove(KEY_I2P_RPC_ADDRESS)
            .remove(KEY_I2P_HTTP_PROXY)
            .apply()
    }

    /**
     * Convenience: dump current effective values for logging/diagnostics.
     */
    @JvmStatic
    fun snapshot(context: Context): Snapshot {
        return Snapshot(
            gapLimit = gapLimit(context),
            accountGap = accountGap(context),
            requireDeviceAuth = requireDeviceAuth(context),
            networkPolicy = networkPolicy(context),
            i2pRpcAddress = i2pRpcAddress(context),
            i2pHttpProxyAddress = i2pHttpProxyAddress(context),
        )
    }

    data class Snapshot(
        val gapLimit: Int,
        val accountGap: Int,
        val requireDeviceAuth: Boolean,
        val networkPolicy: NetworkPolicy,
        val i2pRpcAddress: String,
        val i2pHttpProxyAddress: String?,
    )

    private fun clamp(v: Int, lo: Int, hi: Int): Int = max(lo, min(v, hi))

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        return if (trimmed.endsWith(":443")) "https://$trimmed" else "http://$trimmed"
    }
}
