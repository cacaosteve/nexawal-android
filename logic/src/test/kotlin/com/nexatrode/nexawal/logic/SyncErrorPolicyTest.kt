package com.nexatrode.nexawal.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncErrorPolicyTest {
    @Test
    fun onlyTransportFailuresAreCalledNodeUnreachable() {
        assertEquals(
            SyncErrorKind.FAILED,
            SyncErrorPolicy.classify("refresh already running for wallet"),
        )
        assertEquals(SyncErrorKind.FAILED, SyncErrorPolicy.classify("cache JSON was malformed"))
        assertEquals(
            SyncErrorKind.NODE_UNREACHABLE,
            SyncErrorPolicy.classify("connection refused by peer"),
        )
    }

    @Test
    fun typedStallWinsOverMessageGuessing() {
        assertEquals(SyncErrorKind.STALLED, SyncErrorPolicy.classify("anything", stalled = true))
        assertEquals(
            SyncErrorKind.STALLED,
            SyncErrorPolicy.classify("Sync stalled: no scan progress"),
        )
    }
}
