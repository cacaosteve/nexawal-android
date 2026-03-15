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
 * - Network policy / I2P settings can be added later; this is the minimal parity set.
 */
object MoneroConfig {

    // SharedPreferences file name (scoped to app).
    private const val PREFS_NAME: String = "monero_config"

    // Keys (match iOS naming semantics where possible).
    private const val KEY_GAP_LIMIT: String = "monero_gap_limit"
    private const val KEY_ACCOUNT_GAP: String = "walletcore_account_gap"
    private const val KEY_REQUIRE_DEVICE_AUTH: String = "wallet_require_device_auth"

    // Defaults (match iOS MoneroConfig.swift).
    const val DEFAULT_GAP_LIMIT: Int = 50
    const val DEFAULT_ACCOUNT_GAP: Int = 1
    const val DEFAULT_REQUIRE_DEVICE_AUTH: Boolean = false

    // Safety clamps.
    private const val GAP_LIMIT_MIN: Int = 1
    private const val GAP_LIMIT_MAX: Int = 100_000
    private const val ACCOUNT_GAP_MIN: Int = 1
    private const val ACCOUNT_GAP_MAX: Int = 1_000

    private fun prefs(context: Context): SharedPreferences {
        // Use applicationContext to avoid leaking Activities.
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

    /**
     * Reset both values back to defaults (useful for debugging).
     */
    @JvmStatic
    fun resetToDefaults(context: Context) {
        prefs(context).edit()
            .putInt(KEY_GAP_LIMIT, DEFAULT_GAP_LIMIT)
            .putInt(KEY_ACCOUNT_GAP, DEFAULT_ACCOUNT_GAP)
            .putBoolean(KEY_REQUIRE_DEVICE_AUTH, DEFAULT_REQUIRE_DEVICE_AUTH)
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
        )
    }

    data class Snapshot(
        val gapLimit: Int,
        val accountGap: Int,
        val requireDeviceAuth: Boolean,
    )

    private fun clamp(v: Int, lo: Int, hi: Int): Int = max(lo, min(v, hi))
}
