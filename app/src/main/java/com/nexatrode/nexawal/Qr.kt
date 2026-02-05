package com.nexatrode.nexawal

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URLEncoder

/**
 * QR code + Monero URI utilities.
 *
 * This is used by the Receive flow to generate:
 * - a Monero payment URI string (monero:<address>?tx_amount=...&tx_description=...)
 * - a QR code bitmap for that URI
 *
 * Notes:
 * - Amount is expressed in XMR decimal form as a string (e.g. "1.2345").
 * - Description is URL-encoded.
 * - We keep the URI builder conservative and minimal.
 */
object MoneroQr {

    /**
     * Build a Monero payment URI.
     *
     * Common fields used by wallet URIs:
     * - address (required)
     * - tx_amount (optional)
     * - tx_description (optional)
     *
     * Examples:
     * - monero:44... (address only)
     * - monero:44...?tx_amount=0.1234
     * - monero:44...?tx_amount=0.1234&tx_description=Thanks
     */
    fun buildUri(
        address: String,
        amountXmr: String? = null,
        description: String? = null,
    ): String {
        val addr = address.trim()
        require(addr.isNotEmpty()) { "address must not be empty" }

        val params = buildList {
            val amt = amountXmr?.trim().orEmpty()
            if (amt.isNotEmpty()) {
                // Do not attempt to normalize numeric formatting here; the caller should provide a wallet-friendly value.
                add("tx_amount=${urlEncode(amt)}")
            }

            val desc = description?.trim().orEmpty()
            if (desc.isNotEmpty()) {
                add("tx_description=${urlEncode(desc)}")
            }
        }

        return if (params.isEmpty()) {
            "monero:$addr"
        } else {
            "monero:$addr?${params.joinToString("&")}"
        }
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    /**
     * Generate a QR code bitmap for the given content string.
     *
     * @param content the string to encode (e.g. Monero URI)
     * @param sizePx output bitmap width/height in pixels (square)
     * @param margin QR "quiet zone" modules around the code (defaults to 1)
     * @param errorCorrection QR error correction level (defaults to M)
     */
    fun qrBitmap(
        content: String,
        sizePx: Int = 512,
        margin: Int = 1,
        errorCorrection: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
    ): Bitmap {
        val text = content.trim()
        require(text.isNotEmpty()) { "content must not be empty" }
        require(sizePx > 0) { "sizePx must be > 0" }
        require(margin >= 0) { "margin must be >= 0" }

        val hints = mapOf(
            EncodeHintType.MARGIN to margin,
            EncodeHintType.ERROR_CORRECTION to errorCorrection,
        )

        val matrix = MultiFormatWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            hints
        )

        return matrix.toBitmap()
    }
}

/**
 * Convert a ZXing BitMatrix into an Android Bitmap.
 */
private fun BitMatrix.toBitmap(
    foreground: Int = Color.BLACK,
    background: Int = Color.WHITE,
): Bitmap {
    val width = this.width
    val height = this.height

    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val offset = y * width
        for (x in 0 until width) {
            pixels[offset + x] = if (this[x, y]) foreground else background
        }
    }

    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}
