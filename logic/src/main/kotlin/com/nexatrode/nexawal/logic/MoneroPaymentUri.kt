package com.nexatrode.nexawal.logic

/**
 * Parsed `monero:` payment URI.
 *
 * `spend_key` / `view_key` query params are ignored and must never become send targets.
 */
data class MoneroPaymentUri(
    val address: String,
    val amountXmr: String?,
    val description: String?,
    val recipientName: String?,
) {
    companion object {
        fun parse(raw: String): MoneroPaymentUri? {
            val trimmed = raw.trim()
            if (!trimmed.lowercase().startsWith("monero:")) return null

            var remainder = trimmed.substring("monero:".length)
            if (remainder.startsWith("//")) {
                remainder = remainder.drop(2)
            }

            val queryIndex = remainder.indexOf('?')
            val addressCandidate = if (queryIndex >= 0) remainder.substring(0, queryIndex) else remainder
            val queryString = if (queryIndex >= 0) remainder.substring(queryIndex + 1) else null

            val address = addressCandidate.trim('/').trim()
            if (address.isEmpty()) return null

            var amountXmr: String? = null
            var amountPiconero: Long? = null
            var description: String? = null
            var recipientName: String? = null
            if (!queryString.isNullOrEmpty()) {
                for (pair in queryString.split('&')) {
                    val eq = pair.indexOf('=')
                    val name = (if (eq >= 0) pair.substring(0, eq) else pair).lowercase()
                    val rawValue = if (eq >= 0) pair.substring(eq + 1) else ""
                    if (name == "spend_key" || name == "view_key" || name == "spendkey" || name == "viewkey") {
                        continue
                    }
                    if ((name == "amount" || name == "tx_amount") && rawValue.isNotEmpty()) {
                        val decoded = decode(rawValue, plusAsSpace = false)
                        val pico = parseAmountPiconero(decoded) ?: return null
                        when (val existing = amountPiconero) {
                            null -> {
                                amountPiconero = pico
                                amountXmr = decoded
                            }
                            pico -> Unit
                            else -> return null
                        }
                    } else if ((name == "tx_description" || name == "message") &&
                        rawValue.isNotEmpty() && description == null
                    ) {
                        description = decode(rawValue, plusAsSpace = true)
                    } else if (name == "recipient_name" && rawValue.isNotEmpty() && recipientName == null) {
                        recipientName = decode(rawValue, plusAsSpace = true)
                    }
                }
            }

            return MoneroPaymentUri(
                address = address,
                amountXmr = amountXmr,
                description = description,
                recipientName = recipientName,
            )
        }

        /** Cheap UI gate; WalletCore still validates the network and checksum. */
        fun hasCompleteAddressShape(raw: String): Boolean {
            val address = raw.trim()
            return (address.length == 95 || address.length == 106) &&
                (address.startsWith('4') || address.startsWith('8'))
        }

        /** Plain decimal XMR → piconero; max 12 fractional digits, checked Long range. */
        fun parseAmountPiconero(value: String): Long? {
            val s = value.trim()
            if (s.isEmpty() || s.startsWith('+') || s.startsWith('-')) return null
            val parts = s.split('.', limit = 2)
            val wholeS = parts[0]
            val fracS = if (parts.size > 1) parts[1] else ""
            if (wholeS.isEmpty() || wholeS.any { it !in '0'..'9' }) return null
            if (fracS.length > 12 || fracS.any { it !in '0'..'9' }) return null
            val whole = wholeS.toLongOrNull() ?: return null
            var frac = if (fracS.isEmpty()) 0L else fracS.toLongOrNull() ?: return null
            repeat(12 - fracS.length) {
                val next = frac * 10
                if (next / 10 != frac) return null
                frac = next
            }
            val wholePico = try {
                Math.multiplyExact(whole, 1_000_000_000_000L)
            } catch (_: ArithmeticException) {
                return null
            }
            return try {
                Math.addExact(wholePico, frac)
            } catch (_: ArithmeticException) {
                null
            }
        }

        fun build(
            address: String,
            amountXmr: String? = null,
            description: String? = null,
        ): String {
            val trimmedAddress = address.trim()
            require(trimmedAddress.isNotEmpty()) { "address must not be empty" }
            val params = buildList {
                amountXmr?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    add("tx_amount=${encode(it)}")
                }
                description?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    add("tx_description=${encode(it)}")
                }
            }
            val base = "monero:$trimmedAddress"
            return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
        }

        private fun decode(value: String, plusAsSpace: Boolean): String =
            try {
                val normalized = if (plusAsSpace) value.replace("+", "%20") else value.replace("+", "%2B")
                java.net.URLDecoder.decode(normalized, Charsets.UTF_8.name())
            } catch (_: Exception) {
                value
            }

        private fun encode(value: String): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val unsigned = byte.toInt() and 0xff
                val unreserved = unsigned in 'A'.code..'Z'.code ||
                    unsigned in 'a'.code..'z'.code ||
                    unsigned in '0'.code..'9'.code ||
                    unsigned == '-'.code || unsigned == '_'.code ||
                    unsigned == '.'.code || unsigned == '~'.code
                if (unreserved) {
                    append(unsigned.toChar())
                } else {
                    append('%')
                    append("0123456789ABCDEF"[unsigned ushr 4])
                    append("0123456789ABCDEF"[unsigned and 0x0f])
                }
            }
        }
    }
}
