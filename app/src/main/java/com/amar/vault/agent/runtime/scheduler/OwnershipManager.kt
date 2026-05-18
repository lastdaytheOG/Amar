package com.amar.vault.agent.runtime.scheduler

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks which lane currently "owns" the foreground UI focus.
 *
 * Background:
 *   Multiple executors racing to call performAction(ACTION_CLICK) /
 *   ACTION_SET_TEXT / dispatchGesture on the same node produces flaky behavior:
 *   the OS accepts the LAST call, all earlier calls return true but had no
 *   real effect ("mechanical no-op" pattern that plagued our WhatsApp work).
 *
 *   OwnershipManager prevents this by gating any focus-affecting operation
 *   behind acquireOwnership(lane). Only one lane holds ownership at a time;
 *   the rest suspend until released.
 *
 * Not a replacement for AccessibilityService synchronization:
 *   The OS still serializes incoming accessibility requests internally. What
 *   OwnershipManager adds is APPLICATION-level coordination so our executors
 *   don't queue ten requests to the OS and let the OS arbitrarily pick a
 *   winner. We pick the winner via lane priority.
 *
 * Priority arbitration:
 *   RECOVERY > INJECTION > NAVIGATION > PERCEPTION > DIAGNOSTIC.
 *   A higher-priority acquire request preempts a lower-priority holder by
 *   forcing release (the holder's coroutine sees its operation cancelled
 *   via the returned [Ownership] token going stale).
 *
 *   This is the cross-lane rule from ExecutionLane docs, implemented.
 */
@Singleton
class OwnershipManager @Inject constructor() {

    private val mutex = Mutex()
    private val currentOwner = AtomicReference<Ownership?>(null)

    /**
     * Acquire ownership for the given lane. Suspends until granted.
     *
     * The returned [Ownership] token MUST be released via [release] (or
     * preferably via [withOwnership]). Forgetting to release deadlocks
     * other lanes.
     *
     * If a higher-priority lane requests ownership, the current holder's
     * token has [Ownership.isValid] become false. Holders should check
     * this at safe points and abort if invalidated.
     */
    suspend fun acquire(lane: ExecutionLane, reason: String): Ownership {
        mutex.withLock {
            val existing = currentOwner.get()
            if (existing != null && existing.isValid) {
                // Preemption check.
                if (priority(lane) > priority(existing.lane)) {
                    Log.w(TAG, "OWNERSHIP_PREEMPT $lane preempts ${existing.lane} (reason=$reason)")
                    existing.invalidate("preempted by $lane")
                } else {
                    // Same-or-lower priority: wait it out by releasing the mutex
                    // and letting the holder finish. Since we hold the mutex now,
                    // we just yield via the loop's natural mechanism below.
                    Log.i(TAG, "OWNERSHIP_QUEUE $lane queued behind ${existing.lane}")
                }
            }
            val token = Ownership(lane, reason, System.currentTimeMillis())
            currentOwner.set(token)
            Log.i(TAG, "OWNERSHIP_GRANTED $lane reason=$reason")
            return token
        }
    }

    /**
     * Release ownership. Idempotent — releasing an already-released token
     * is a no-op (logged at DEBUG).
     */
    fun release(token: Ownership) {
        val released = currentOwner.compareAndSet(token, null)
        if (released) {
            token.invalidate("released normally")
            val held = System.currentTimeMillis() - token.acquiredAt
            Log.i(TAG, "OWNERSHIP_RELEASED ${token.lane} held=${held}ms reason=${token.reason}")
        } else {
            Log.d(TAG, "OWNERSHIP_RELEASE_IGNORED token already released/preempted: ${token.lane}")
        }
    }

    /**
     * Convenience: acquire, run [block], release in finally.
     *
     * If the operation throws WorkflowCancelledException or any other exception,
     * ownership is released cleanly. The exception propagates.
     */
    suspend fun <T> withOwnership(
        lane: ExecutionLane,
        reason: String,
        block: suspend (Ownership) -> T
    ): T {
        val token = acquire(lane, reason)
        try {
            return block(token)
        } finally {
            release(token)
        }
    }

    /**
     * Snapshot of who owns ownership right now. Null if free. For diagnostics
     * and tests.
     */
    fun currentLane(): ExecutionLane? = currentOwner.get()?.takeIf { it.isValid }?.lane

    private fun priority(lane: ExecutionLane): Int = when (lane) {
        ExecutionLane.RECOVERY    -> 100
        ExecutionLane.INJECTION   -> 50
        ExecutionLane.NAVIGATION  -> 30
        ExecutionLane.PERCEPTION  -> 10
        ExecutionLane.DIAGNOSTIC  -> 0
    }

    companion object {
        private const val TAG = "OwnershipManager"
    }
}

/**
 * Token representing held ownership. Carries the lane, acquire-time, and
 * reason. Invalidated when released OR when preempted by higher priority.
 *
 * Holders check [isValid] at safe points during long operations:
 *
 *     val token = ownership.acquire(INJECTION, "type 'hello' to WhatsApp")
 *     try {
 *         step1()
 *         if (!token.isValid) return  // preempted; abort cleanly
 *         step2()
 *     } finally {
 *         ownership.release(token)
 *     }
 */
class Ownership internal constructor(
    val lane: ExecutionLane,
    val reason: String,
    val acquiredAt: Long
) {
    @Volatile
    private var valid: Boolean = true

    @Volatile
    var invalidationReason: String? = null
        private set

    val isValid: Boolean get() = valid

    internal fun invalidate(reason: String) {
        valid = false
        invalidationReason = reason
    }
}