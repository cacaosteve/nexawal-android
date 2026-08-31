package com.nexatrode.nexawal.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mutex-based single-flight for long-running refresh work.
 *
 * Concurrent [run] callers share one job: the first starts [block], later callers await the
 * same [Job] without starting a second [block].
 */
class RefreshSingleFlight {
    private val mutex = Mutex()
    @Volatile
    private var inFlight: Job? = null

    suspend fun run(scope: CoroutineScope, block: suspend () -> Unit) {
        val job = mutex.withLock {
            inFlight?.takeIf { it.isActive }?.let { return@withLock it }
            scope.launch { block() }.also { inFlight = it }
        }
        try {
            job.join()
        } finally {
            mutex.withLock {
                if (inFlight === job && !job.isActive) {
                    inFlight = null
                }
            }
        }
    }

    fun isActive(): Boolean = inFlight?.isActive == true
}
