package com.nexatrode.nexawal

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Timestamp formatting helpers matching iOS `WalletView` behavior.
 *
 * iOS rules (from `WalletView.swift`):
 * - If timestamp is missing/0 -> nil
 * - Compute delta seconds = now - ts
 * - If delta < 0 -> absolute
 * - If delta < 10 -> "just now"
 * - If delta < 60 -> "<Ns> ago"
 * - If minutes < 60 -> "<Nm> ago"
 * - If hours < 24 -> "<Nh> ago"
 * - If days < 7 -> "<Nd> ago"
 * - Else -> absolute (short date + short time)
 *
 * Notes:
 * - Input timestamps are expected to be seconds since epoch (Monero core uses seconds).
 * - We intentionally avoid Android framework dependencies (works on JVM/Android).
 */
object TimeFormat {

    /**
     * Format a UNIX timestamp (seconds since epoch) into a relative string like:
     * - "just now"
     * - "42s ago"
     * - "12m ago"
     * - "3h ago"
     * - "5d ago"
     *
     * Returns null if tsSeconds is null or <= 0.
     */
    @JvmStatic
    fun relative(tsSeconds: Long?, nowSeconds: Long = Instant.now().epochSecond): String? {
        val ts = tsSeconds ?: return null
        if (ts <= 0L) return null

        val deltaSeconds = (nowSeconds - ts).toInt()

        // Future timestamps shouldn't happen; fall back to absolute formatting.
        if (deltaSeconds < 0) {
            return absolute(tsSeconds)
        }

        if (deltaSeconds < 10) return "just now"
        if (deltaSeconds < 60) return "${deltaSeconds}s ago"

        val minutes = deltaSeconds / 60
        if (minutes < 60) return "${minutes}m ago"

        val hours = minutes / 60
        if (hours < 24) return "${hours}h ago"

        val days = hours / 24
        if (days < 7) return "${days}d ago"

        return absolute(tsSeconds)
    }

    /**
     * Absolute short timestamp (date + time), intended for details views and accessibility.
     *
     * Returns null if tsSeconds is null or <= 0.
     */
    @JvmStatic
    fun absolute(
        tsSeconds: Long?,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String? {
        val ts = tsSeconds ?: return null
        if (ts <= 0L) return null

        val instant = Instant.ofEpochSecond(ts)
        val formatter = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(zoneId)

        return formatter.format(instant)
    }

    /**
     * Convenience: returns a pair (relative, absolute) where both may be null if timestamp missing.
     */
    @JvmStatic
    fun both(tsSeconds: Long?): Pair<String?, String?> {
        val abs = absolute(tsSeconds)
        val rel = relative(tsSeconds)
        return rel to abs
    }
}
