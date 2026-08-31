package com.nexatrode.nexawal.logic

enum class SyncErrorKind {
    STALLED,
    NODE_UNREACHABLE,
    FAILED,
}

/** Keeps cache, busy-state, and protocol failures from being presented as node outages. */
object SyncErrorPolicy {
    fun classify(message: String, stalled: Boolean = false): SyncErrorKind {
        val lower = message.lowercase()
        if (stalled || "sync stalled" in lower || "no scan progress" in lower) {
            return SyncErrorKind.STALLED
        }

        val transportPatterns = listOf(
            "connection refused",
            "connection reset",
            "connection timed out",
            "timed out",
            "timeout/disconnect",
            "failed to connect",
            "could not connect",
            "couldn't connect",
            "network is unreachable",
            "node unreachable",
            "not reachable",
            "no route to host",
            "name or service not known",
            "transport error",
            "dns",
            "tls handshake",
        )
        return if (transportPatterns.any { it in lower }) {
            SyncErrorKind.NODE_UNREACHABLE
        } else {
            SyncErrorKind.FAILED
        }
    }
}
