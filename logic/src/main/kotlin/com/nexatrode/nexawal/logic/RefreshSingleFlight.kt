package com.nexatrode.nexawal.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Mutex-based single-flight for long-running refresh work.
 *
 * Concurrent [run] callers share one job: the first runs [prepareUnderLock] then starts
 * [block]; later callers await the same [Job] without starting a second [block].
 *
 * [prepareUnderLock] runs only for the starter, while holding the mutex and before the job
 * is launched — use it for CAS flags / UI state that must not race with joiners.
 */
class RefreshSingleFlight {
    private val mutex = Mutex()
    @Volatile
    private var inFlight: Job? = null

    suspend fun run(
        scope: CoroutineScope,
        context: CoroutineContext = EmptyCoroutineContext,
        prepareUnderLock: () -> Unit = {},
        onStarted: (Job) -> Unit = {},
        block: suspend () -> Unit,
    ) {
        val job = mutex.withLock {
            inFlight?.takeIf { it.isActive }?.let { return@withLock it }
            prepareUnderLock()
            scope.launch(context) { block() }.also { started ->
                inFlight = started
                onStarted(started)
            }
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

    fun currentJob(): Job? = inFlight?.takeIf { it.isActive }
}
