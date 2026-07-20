package com.nexatrode.nexawal.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SendSafetyTest {

    @Test
    fun unlockedPrecheckAcceptsAmountPlusFee() {
        assertTrue(SendSafety.hasUnlockedForExactSend(1_000L, 100L, 1_100L))
        assertTrue(SendSafety.hasUnlockedForExactSend(1_000L, 100L, 1_000_000L))
    }

    @Test
    fun unlockedPrecheckRejectsInsufficientAndOverflow() {
        assertFalse(SendSafety.hasUnlockedForExactSend(1_000L, 100L, 1_099L))
        assertFalse(SendSafety.hasUnlockedForExactSend(2_000L, 0L, 1_000L))
        assertFalse(SendSafety.hasUnlockedForExactSend(-1L, 0L, 100L))
        assertFalse(SendSafety.hasUnlockedForExactSend(Long.MAX_VALUE, 1L, Long.MAX_VALUE))
    }

    @Test
    fun feeRateDetection() {
        assertTrue(SendSafety.isFeeRateFailure("fee_rate failed: timeout"))
        assertTrue(SendSafety.isFeeRateFailure("RPC FEE_RATE_FAILED"))
        assertFalse(SendSafety.isFeeRateFailure("connection refused"))
    }

    @Test
    fun postBroadcastMarkersBlockRetry() {
        assertTrue(SendSafety.looksLikePostBroadcastOrSpendFailure("key image already spent"))
        assertTrue(SendSafety.looksLikePostBroadcastOrSpendFailure("failed to broadcast tx"))
        assertTrue(SendSafety.looksLikePostBroadcastOrSpendFailure("txid=abc"))
        assertFalse(SendSafety.looksLikePostBroadcastOrSpendFailure("fee_rate failed"))
    }

    @Test
    fun siblingRetryOnlyForPreBroadcastFeeRateOnCupratePort() {
        val cuprate = "http://127.0.0.1:18092"
        assertEquals(
            "http://127.0.0.1:18081",
            SendSafety.shouldRetryViaSiblingMonerod("fee_rate failed", "", cuprate),
        )
        assertNull(
            SendSafety.shouldRetryViaSiblingMonerod(
                "fee_rate failed",
                "key image already spent",
                cuprate,
            ),
        )
        assertNull(
            SendSafety.shouldRetryViaSiblingMonerod("fee_rate failed", "", "http://127.0.0.1:18081"),
        )
        assertNull(
            SendSafety.shouldRetryViaSiblingMonerod("connection refused", "", cuprate),
        )
    }
}

class SendGateTest {

    @Test
    fun secondConcurrentLockFailsFast() {
        val gate = SendGate()
        assertTrue(gate.tryBegin())
        try {
            gate.withLock { fail("second lock should not run") }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals(SendGate.ALREADY_IN_PROGRESS, e.message)
        } finally {
            gate.end()
        }
        gate.withLock { assertTrue(true) }
    }
}
