package com.nexatrode.nexawal.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LastKnownGoodPolicyTest {
    @Test
    fun failedCandidatePreservesExistingHistory() {
        val existing = listOf("tx-1", "tx-2")
        val decision = LastKnownGoodPolicy.choose<List<String>>(
            existing,
            Result.failure(IllegalArgumentException("malformed transfer JSON")),
        )

        assertFalse(decision.accepted)
        assertEquals(existing, decision.value)
        assertEquals("malformed transfer JSON", decision.errorMessage)
    }

    @Test
    fun validCandidateReplacesExistingHistory() {
        val decision = LastKnownGoodPolicy.choose(
            listOf("old"),
            Result.success(listOf("new")),
        )

        assertTrue(decision.accepted)
        assertEquals(listOf("new"), decision.value)
        assertEquals(null, decision.errorMessage)
    }
}
