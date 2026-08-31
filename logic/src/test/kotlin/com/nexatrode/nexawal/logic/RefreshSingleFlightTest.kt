package com.nexatrode.nexawal.logic

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RefreshSingleFlightTest {
    @Test
    fun simultaneousCallersShareOneExecution() = runBlocking {
        val flight = RefreshSingleFlight()
        val starts = AtomicInteger(0)
        val prepares = AtomicInteger(0)
        val finished = AtomicInteger(0)
        val prepareSawInactive = AtomicBoolean(true)

        val first = async {
            flight.run(
                scope = this,
                prepareUnderLock = {
                    prepares.incrementAndGet()
                    if (flight.isActive()) prepareSawInactive.set(false)
                },
            ) {
                starts.incrementAndGet()
                delay(80)
                finished.incrementAndGet()
            }
        }
        delay(10)
        val second = async {
            flight.run(
                scope = this,
                prepareUnderLock = { prepares.incrementAndGet() },
            ) {
                starts.incrementAndGet()
                delay(80)
                finished.incrementAndGet()
            }
        }
        val third = async {
            flight.run(
                scope = this,
                prepareUnderLock = { prepares.incrementAndGet() },
            ) {
                starts.incrementAndGet()
                delay(80)
                finished.incrementAndGet()
            }
        }

        first.await()
        second.await()
        third.await()

        assertEquals(1, starts.get())
        assertEquals(1, prepares.get())
        assertEquals(1, finished.get())
        assertTrue(prepareSawInactive.get())
        assertTrue(!flight.isActive())
    }
}
