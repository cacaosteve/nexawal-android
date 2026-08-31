package com.nexatrode.nexawal.logic

import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheFileIOTest {
    @Test
    fun textLoadDistinguishesMissingValidAndUnreadableFiles() = withTempDirectory { directory ->
        val journal = directory.resolve("pending.json")
        assertEquals(null, CacheFileIO.readTextIfPresent(journal))

        journal.writeText("signed transaction")
        assertEquals("signed transaction", CacheFileIO.readTextIfPresent(journal))

        journal.delete()
        journal.mkdir()
        assertTrue(runCatching { CacheFileIO.readTextIfPresent(journal) }.isFailure)
    }

    @Test
    fun atomicReplacementPublishesOnlyTheCompleteNewCache() = withTempDirectory { directory ->
        val cache = directory.resolve("main_wallet.cache")
        CacheFileIO.writeAtomically(cache, "first complete cache".toByteArray())
        CacheFileIO.writeAtomically(cache, "second complete cache".toByteArray())

        assertArrayEquals("second complete cache".toByteArray(), cache.readBytes())
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun failedWriteLeavesThePreviousCacheIntact() = withTempDirectory { directory ->
        val cache = directory.resolve("main_wallet.cache")
        CacheFileIO.writeAtomically(cache, "trusted cache".toByteArray())

        val result = runCatching {
            CacheFileIO.writeAtomically(cache) { output ->
                output.write("partial replacement".toByteArray())
                throw IOException("simulated process failure before commit")
            }
        }

        assertTrue(result.isFailure)
        assertArrayEquals("trusted cache".toByteArray(), cache.readBytes())
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun rejectedCachesLeaveTheActiveSlotAndNeverOverwriteEvidence() = withTempDirectory { directory ->
        val cache = directory.resolve("main_wallet.cache")
        cache.writeText("rejected one")
        val first = CacheFileIO.quarantineRejected(cache, timestampMillis = 1234L)!!

        assertFalse(cache.exists())
        assertEquals("rejected one", first.readText())

        cache.writeText("rejected two")
        val second = CacheFileIO.quarantineRejected(cache, timestampMillis = 1234L)!!
        assertNotEquals(first, second)
        assertEquals("rejected two", second.readText())
        assertEquals(null, CacheFileIO.quarantineRejected(cache, timestampMillis = 1234L))
    }

    private fun withTempDirectory(block: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("nexawal-cache-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
