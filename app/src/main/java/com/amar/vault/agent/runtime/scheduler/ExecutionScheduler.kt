package com.amar.vault.agent.runtime.scheduler

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-lane execution scheduler. Wraps a coroutine block in the lane's
 * concurrency rules:
 *   - NAVIGATION, INJECTION, RECOVERY, PERCEPTION: serial (semaphore permit=1)
 *   - DIAGNOSTIC: unbounded
 *
 * What this is NOT:
 *   - Not a thread pool. The underlying dispatcher is Dispatchers.Default —
 *     coroutines, not threads. We don't spawn threads per lane.
 *   - Not a queue with priority preemption. That's OwnershipManager's job.
 *     Scheduler just enforces "one operation per lane at a time."
 *   - Not a side-effect of OwnershipManager. They compose: ownership gates
 *     CROSS-lane coordination, scheduler gates INTRA-lane serialization.
 *
 * Typical usage:
 *
 *     scheduler.run(ExecutionLane.PERCEPTION) {
 *         val snap = walkTree()
 *         publishToBus(snap)
 *     }
 *
 *   Another caller in PERCEPTION lane waits until the first finishes.
 *   A caller in INJECTION lane runs in parallel (different lane).
 *
 * Cancellation:
 *   The block's outer coroutine context is preserved — cancelling the calling
 *   coroutine cancels the lane operation. The semaphore is released even on
 *   exception (try/finally inside run()).
 */
@Singleton
class ExecutionScheduler @Inject constructor() {

    private val laneSemaphores: Map<ExecutionLane, Semaphore?> = mapOf(
        ExecutionLane.NAVIGATION to Semaphore(permits = 1),
        ExecutionLane.INJECTION  to Semaphore(permits = 1),
        ExecutionLane.RECOVERY   to Semaphore(permits = 1),
        ExecutionLane.PERCEPTION to Semaphore(permits = 1),
        ExecutionLane.DIAGNOSTIC to null   // unbounded
    )

    private val dispatcher: CoroutineDispatcher = Dispatchers.Default

    /**
     * Run [block] on the given lane with its concurrency rules. Suspends if
     * another operation is already running on the same lane.
     */
    suspend fun <T> run(lane: ExecutionLane, label: String, block: suspend () -> T): T {
        val sem = laneSemaphores[lane]
        return withContext(dispatcher) {
            if (sem == null) {
                // DIAGNOSTIC lane: unbounded.
                logEnter(lane, label, queued = false)
                try {
                    block()
                } finally {
                    logExit(lane, label)
                }
            } else {
                val queued = sem.availablePermits == 0
                if (queued) logEnter(lane, label, queued = true)
                sem.acquire()
                try {
                    if (!queued) logEnter(lane, label, queued = false)
                    block()
                } finally {
                    sem.release()
                    logExit(lane, label)
                }
            }
        }
    }

    /**
     * Non-blocking inspection — number of currently-running operations on a lane.
     * For DIAGNOSTIC, always 0 (unbounded, untracked).
     */
    fun activeOn(lane: ExecutionLane): Int {
        val sem = laneSemaphores[lane] ?: return 0
        return 1 - sem.availablePermits
    }

    private fun logEnter(lane: ExecutionLane, label: String, queued: Boolean) {
        if (queued) {
            Log.i(TAG, "LANE_QUEUED $lane label=$label")
        } else {
            Log.i(TAG, "LANE_ENTER $lane label=$label")
        }
    }

    private fun logExit(lane: ExecutionLane, label: String) {
        Log.i(TAG, "LANE_EXIT $lane label=$label")
    }

    companion object {
        private const val TAG = "ExecutionScheduler"
    }
}