package com.nexatrode.nexawal.logic

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manager-level in-flight lock so a second concurrent send/sweep fails fast.
 */
class SendGate {
    private val inFlight = AtomicBoolean(false)

    fun tryBegin(): Boolean = inFlight.compareAndSet(false, true)

    fun end() {
        inFlight.set(false)
    }

    fun <T> withLock(block: () -> T): T {
        if (!tryBegin()) {
            throw IllegalStateException(ALREADY_IN_PROGRESS)
        }
        return try {
            block()
        } finally {
            end()
        }
    }

    companion object {
        const val ALREADY_IN_PROGRESS: String =
            "A send is already in progress. Wait for it to finish."
    }
}
