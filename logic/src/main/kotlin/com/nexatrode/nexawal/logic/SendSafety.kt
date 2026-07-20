package com.nexatrode.nexawal.logic

/**
 * Pure send preflight / retry classification helpers (no Android / JNI deps).
 */
object SendSafety {

    /** Overflow-safe check that amount + fee fits in unlocked balance. */
    fun hasUnlockedForExactSend(amountPiconero: Long, feePiconero: Long, unlockedPiconero: Long): Boolean {
        if (amountPiconero < 0L || feePiconero < 0L || unlockedPiconero < 0L) return false
        if (amountPiconero > unlockedPiconero) return false
        return feePiconero <= unlockedPiconero - amountPiconero
    }

    fun isFeeRateFailure(text: String): Boolean {
        val normalized = text.lowercase()
        return normalized.contains("fee_rate failed") || normalized.contains("fee_rate_failed")
    }

    /** Errors that imply construction/broadcast may have progressed past fee estimation. */
    fun looksLikePostBroadcastOrSpendFailure(text: String): Boolean {
        val normalized = text.lowercase()
        val markers = listOf(
            "key image",
            "already spent",
            "double spend",
            "txid",
            "transaction was rejected",
            "failed to broadcast",
            "relay",
            "daemon rejected",
        )
        return markers.any { normalized.contains(it) }
    }

    /**
     * Cuprate (18092) sibling Monero RPC (18081) fallback URL, or null if not applicable.
     */
    fun siblingMonerodUrlIfNeeded(endpoint: String): String? {
        val uri = runCatching { java.net.URI(endpoint) }.getOrNull() ?: return null
        if (uri.port != 18092) return null
        return java.net.URI(
            uri.scheme,
            uri.userInfo,
            uri.host,
            18081,
            uri.path,
            uri.query,
            uri.fragment,
        ).toString()
    }

    /**
     * Only retry on fee_rate failures that clearly happened before spend/broadcast signals.
     */
    fun shouldRetryViaSiblingMonerod(
        errorText: String,
        coreMessage: String,
        endpoint: String,
    ): String? {
        val fallback = siblingMonerodUrlIfNeeded(endpoint) ?: return null
        val combined = "$errorText\n$coreMessage"
        if (looksLikePostBroadcastOrSpendFailure(combined)) return null
        if (isFeeRateFailure(errorText) || isFeeRateFailure(coreMessage)) return fallback
        return null
    }
}
