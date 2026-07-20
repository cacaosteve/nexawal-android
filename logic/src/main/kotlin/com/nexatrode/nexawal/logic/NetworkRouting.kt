package com.nexatrode.nexawal.logic

/**
 * Pure network-policy helpers shared by Settings / WalletManager (no Android Context).
 */
object NetworkRouting {

    enum class Policy {
        CLEARNET,
        I2P,
        HYBRID,
        ;

        companion object {
            fun fromRaw(raw: String?): Policy =
                when (raw?.lowercase()) {
                    "i2p" -> I2P
                    "hybrid" -> HYBRID
                    else -> CLEARNET
                }
        }
    }

    fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        return if (trimmed.endsWith(":443")) "https://$trimmed" else "http://$trimmed"
    }

    fun scanNodeUrl(policy: Policy, clearnetNodeUrl: String, i2pRpcAddress: String): String {
        return when (policy) {
            Policy.CLEARNET, Policy.HYBRID -> clearnetNodeUrl
            Policy.I2P -> normalizeUrl(i2pRpcAddress)
        }
    }

    fun broadcastNodeUrl(policy: Policy, clearnetNodeUrl: String, i2pRpcAddress: String): String {
        return when (policy) {
            Policy.CLEARNET -> clearnetNodeUrl
            Policy.I2P, Policy.HYBRID -> normalizeUrl(i2pRpcAddress)
        }
    }

    /** True when daemon RPC for this policy should go through the I2P HTTP proxy. */
    fun shouldUseI2pHttpProxy(
        policy: Policy,
        proxyConfigured: Boolean,
        forBroadcast: Boolean,
    ): Boolean {
        if (!proxyConfigured) return false
        return when (policy) {
            Policy.CLEARNET -> false
            Policy.I2P -> true
            Policy.HYBRID -> forBroadcast
        }
    }
}
