package com.nexatrode.nexawal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.nexatrode.nexawal.walletcore.WalletCore as WalletCoreApi

/**
 * Models + helpers for the Send screen and WalletManager send flows.
 *
 * Rationale:
 * - The :walletcore-api module is intentionally kept light and plugin-free.
 * - The app module already uses kotlinx.serialization (for transfers parsing),
 *   so we define send/sweep JSON DTOs and encoding/decoding here.
 *
 * These DTOs match the walletcore C-ABI JSON schemas:
 * - wallet_preview_fee -> { "fee": <uint64> }
 * - wallet_send        -> { "txid": "<hex>", "fee": <uint64> }
 * - wallet_prepare_send -> { "txid", "amount", "fee", "signed_tx_hex" }
 * - wallet_relay_prepared -> { "txid", "status": "accepted"|"already_known" }
 * - wallet_preview_sweep -> { "amount": <uint64>, "fee": <uint64> }
 * - wallet_sweep         -> { "txid": "<hex>", "amount": <uint64>, "fee": <uint64> }
 *
 * Amounts are piconero (1 XMR = 1e12 piconero).
 */
object SendJson {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /**
     * Destination for fee preview / multi-send JSON array:
     *   [ { "address": "<addr>", "amount": <uint64> }, ... ]
     */
    @Serializable
    data class Destination(
        val address: String,
        val amount: Long,
    )

    @Serializable
    data class FeeResult(
        val fee: Long,
    ) {
        val feeXmr: String get() = WalletCoreApi.formatXmr(fee)
    }

    @Serializable
    data class SendResult(
        val txid: String,
        val fee: Long,
    ) {
        val feeXmr: String get() = WalletCoreApi.formatXmr(fee)
        fun txidShort(prefix: Int = 12): String = if (txid.length <= prefix) txid else txid.take(prefix)
    }

    @Serializable
    data class PreparedSend(
        val txid: String,
        val amount: Long,
        val fee: Long,
        @kotlinx.serialization.SerialName("signed_tx_hex")
        val signedTxHex: String,
    )

    @Serializable
    data class RelayResult(
        val txid: String,
        val status: String,
    )

    /** Durable envelope written under WalletCaches before relay. */
    @Serializable
    data class PendingPreparedEnvelope(
        val nodeUrl: String,
        val prepared: PreparedSend,
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    @Serializable
    data class SweepPreviewResult(
        val amount: Long,
        val fee: Long,
    ) {
        val amountXmr: String get() = WalletCoreApi.formatXmr(amount)
        val feeXmr: String get() = WalletCoreApi.formatXmr(fee)
    }

    @Serializable
    data class SweepSendResult(
        val txid: String,
        val amount: Long,
        val fee: Long,
    ) {
        val amountXmr: String get() = WalletCoreApi.formatXmr(amount)
        val feeXmr: String get() = WalletCoreApi.formatXmr(fee)
        fun txidShort(prefix: Int = 12): String = if (txid.length <= prefix) txid else txid.take(prefix)
    }

    fun encodeDestinations(destinations: List<Destination>): String {
        require(destinations.isNotEmpty()) { "destinations must not be empty" }
        destinations.forEach {
            require(it.address.isNotBlank()) { "destination address must not be blank" }
            require(it.amount > 0L) { "destination amount must be > 0" }
        }
        return json.encodeToString(ListSerializerDestination, destinations)
    }

    fun decodeFeeResult(raw: String): FeeResult =
        json.decodeFromString(FeeResult.serializer(), raw)

    fun decodeSendResult(raw: String): SendResult =
        json.decodeFromString(SendResult.serializer(), raw)

    fun decodePreparedSend(raw: String): PreparedSend =
        json.decodeFromString(PreparedSend.serializer(), raw)

    fun encodePreparedSend(prepared: PreparedSend): String =
        json.encodeToString(PreparedSend.serializer(), prepared)

    fun decodeRelayResult(raw: String): RelayResult =
        json.decodeFromString(RelayResult.serializer(), raw)

    fun encodePendingPreparedEnvelope(envelope: PendingPreparedEnvelope): String =
        json.encodeToString(PendingPreparedEnvelope.serializer(), envelope)

    fun decodePendingPreparedEnvelope(raw: String): PendingPreparedEnvelope =
        json.decodeFromString(PendingPreparedEnvelope.serializer(), raw)

    fun decodeSweepPreviewResult(raw: String): SweepPreviewResult =
        json.decodeFromString(SweepPreviewResult.serializer(), raw)

    fun decodeSweepSendResult(raw: String): SweepSendResult =
        json.decodeFromString(SweepSendResult.serializer(), raw)

    /**
     * Kotlinx serialization needs an explicit List serializer when encoding lists without reified generics.
     */
    private val ListSerializerDestination = kotlinx.serialization.builtins.ListSerializer(Destination.serializer())
}
