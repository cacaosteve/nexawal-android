package com.nexatrode.nexawal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Transfer model matching the walletcore JSON schema (from `wallet_list_transfers_json`).
 *
 * Expected schema (subject to evolution in the native core):
 * [
 *   {
 *     "txid": "<hex>",
 *     "direction": "in" | "out" | "self",
 *     "amount": <uint64>,
 *     "fee": <uint64|null>,
 *     "height": <uint64|null>,
 *     "timestamp": <uint64|null>,
 *     "confirmations": <uint64>,
 *     "is_pending": <bool>,
 *     "subaddress_major": <uint32|null>,
 *     "subaddress_minor": <uint32|null>
 *   },
 *   ...
 * ]
 *
 * Notes:
 * - Kotlin uses signed Long; values are expected to fit within signed 64-bit range.
 * - Field names use snake_case; we map them explicitly via @SerialName.
 */
@Serializable
data class Transfer(
    val txid: String,
    val direction: String,
    val amount: Long,
    val fee: Long? = null,
    val height: Long? = null,
    val timestamp: Long? = null,
    val confirmations: Long = 0,
    @SerialName("is_pending")
    val isPending: Boolean = false,
    @SerialName("subaddress_major")
    val subaddressMajor: Int? = null,
    @SerialName("subaddress_minor")
    val subaddressMinor: Int? = null,
) {
    /**
     * Convenience: treat pending as (isPending || confirmations == 0).
     */
    val pending: Boolean
        get() = isPending || confirmations == 0L

    /**
     * Convenience: short txid for UI.
     */
    fun txidShort(prefix: Int = 10): String =
        if (txid.length <= prefix) txid else txid.take(prefix)

    /**
     * Amount formatted as XMR with 12 fractional digits.
     */
    fun amountXmr(): String = XmrFormat.formatPiconeroAsXmr(amount)

    /**
     * Fee formatted as XMR with 12 fractional digits (if present).
     */
    fun feeXmr(): String? = fee?.let(XmrFormat::formatPiconeroAsXmr)

    /**
     * Direction label normalization helper.
     */
    fun directionLabel(): String = when (direction.lowercase()) {
        "in" -> "Received"
        "out" -> "Sent"
        "self" -> "Self"
        else -> direction
    }
}

/**
 * XMR formatting utilities for amounts stored in piconero.
 *
 * 1 XMR = 1_000_000_000_000 piconero (1e12).
 */
object XmrFormat {
    private const val PICONERO_PER_XMR: Long = 1_000_000_000_000L

    /**
     * Format a piconero amount as a decimal XMR string with 12 fractional digits.
     *
     * Examples:
     * - 0 -> "0.000000000000"
     * - 1 -> "0.000000000001"
     * - 1_000_000_000_000 -> "1.000000000000"
     */
    @JvmStatic
    fun formatPiconeroAsXmr(piconero: Long): String {
        // Use BigDecimal to avoid floating point precision issues.
        return BigDecimal(piconero)
            .divide(BigDecimal(PICONERO_PER_XMR), 12, RoundingMode.DOWN)
            .toPlainString()
    }

    /**
     * Format a piconero amount for primary UI surfaces.
     *
     * Wallet home should not show full atomic precision by default. Use a
     * shorter fixed scale for the main balance while preserving exact values in
     * detail views.
     */
    @JvmStatic
    fun formatPiconeroAsDisplayXmr(piconero: Long, decimals: Int = 6): String {
        return BigDecimal(piconero)
            .divide(BigDecimal(PICONERO_PER_XMR), decimals, RoundingMode.DOWN)
            .toPlainString()
    }
}
