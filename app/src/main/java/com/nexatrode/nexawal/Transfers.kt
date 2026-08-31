package com.nexatrode.nexawal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Transfer model matching the walletcore JSON schema (from `wallet_list_transfers_json`).
 *
 * WalletCore v1 wraps rows in a versioned snapshot:
 * {
 *   "schema_version": 1,
 *   "wallet_id": "main_wallet",
 *   "last_scanned_height": <uint64>,
 *   "chain_height": <uint64>,
 *   "chain_time": <uint64>,
 *   "transfers": [ {
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
 *   } ]
 * }
 *
 * The decoder below also accepts the historical bare row array as schema v0.
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
}

@Serializable
private data class VersionedTransferHistory(
    @SerialName("schema_version")
    val schemaVersion: Int,
    @SerialName("wallet_id")
    val walletId: String,
    @SerialName("last_scanned_height")
    val lastScannedHeight: Long,
    @SerialName("chain_height")
    val chainHeight: Long,
    @SerialName("chain_time")
    val chainTime: Long,
    val transfers: List<Transfer>,
)

data class TransferHistorySnapshot(
    /** Zero identifies the legacy bare-array payload. */
    val schemaVersion: Int,
    val walletId: String?,
    val lastScannedHeight: Long?,
    val chainHeight: Long?,
    val chainTime: Long?,
    val transfers: List<Transfer>,
)

/** Version-aware decoder for `wallet_list_transfers_json`. */
object TransferHistoryJson {
    private val parser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun decode(raw: String, expectedWalletId: String): TransferHistorySnapshot {
        val snapshot = when (val root = parser.parseToJsonElement(raw)) {
            is JsonArray -> TransferHistorySnapshot(
                schemaVersion = 0,
                walletId = null,
                lastScannedHeight = null,
                chainHeight = null,
                chainTime = null,
                transfers = parser.decodeFromJsonElement(ListSerializer(Transfer.serializer()), root),
            )
            is JsonObject -> {
                val envelope = parser.decodeFromJsonElement<VersionedTransferHistory>(root)
                require(envelope.schemaVersion == 1) {
                    "Unsupported transfer history schema version ${envelope.schemaVersion} (supported: 1)"
                }
                require(envelope.walletId == expectedWalletId) {
                    "Transfer history wallet '${envelope.walletId}' did not match requested wallet '$expectedWalletId'"
                }
                TransferHistorySnapshot(
                    schemaVersion = envelope.schemaVersion,
                    walletId = envelope.walletId,
                    lastScannedHeight = envelope.lastScannedHeight,
                    chainHeight = envelope.chainHeight,
                    chainTime = envelope.chainTime,
                    transfers = envelope.transfers,
                )
            }
            else -> error("Transfer history JSON must be an array or object")
        }

        snapshot.transfers.forEach { transfer ->
            require(transfer.txid.isNotBlank()) { "Transfer history contained an empty transaction id" }
            require(transfer.direction in setOf("in", "out", "self")) {
                "Unsupported transfer direction '${transfer.direction}' for ${transfer.txid}"
            }
            require(transfer.amount >= 0L) { "Transfer amount was negative for ${transfer.txid}" }
            require(transfer.fee == null || transfer.fee >= 0L) { "Transfer fee was negative for ${transfer.txid}" }
            require(transfer.height == null || transfer.height >= 0L) { "Transfer height was negative for ${transfer.txid}" }
            require(transfer.timestamp == null || transfer.timestamp >= 0L) { "Transfer timestamp was negative for ${transfer.txid}" }
            require(transfer.confirmations >= 0L) { "Transfer confirmations were negative for ${transfer.txid}" }
        }
        return snapshot
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
