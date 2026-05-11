package com.amar.vault.agent.control

import com.amar.vault.agent.dsl.Constraints
import kotlin.math.min

/**
 * Decides whether and how to retry a failed attempt.
 *
 * Policy:
 *   - Honor Constraints.retries as max attempts beyond the first.
 *     (retries=2 ⇒ up to 3 total attempts: 1 initial + 2 retries.)
 *   - Exponential backoff with a cap, seeded by Constraints.retryBackoffMs.
 *     Attempt N waits backoffMs * (2^(N-1)), capped at MAX_BACKOFF_MS.
 *   - Some failures are fatal (don't retry): NoExecutor, PermissionDenied,
 *     ValidationRejected. Retrying those just wastes time and battery.
 *   - TargetNotFound is retryable — UI might still be settling.
 *   - Timeout is retryable but gets an extra jitter to avoid thrashing the same path.
 *
 * Jitter:
 *   We add ±20% random jitter on top of the exponential delay. Deterministic
 *   backoff across parallel retries can create synchronized request bursts
 *   (thundering herd) even on a single device — e.g., two tasks both waiting
 *   on the same app to open, both retrying at exactly 300ms.
 */
object RetryPolicy {

    private const val MAX_BACKOFF_MS = 5_000L
    private const val JITTER_RATIO = 0.2

    /**
     * Decide what to do after a failed attempt.
     *
     * @param attemptNumber 1-indexed number of the attempt that JUST failed
     * @param constraints  envelope's effective constraints
     * @param failure      why this attempt failed
     */
    fun decide(
        attemptNumber: Int,
        constraints: Constraints,
        failure: FailureReason
    ): RetryDecision {
        if (isFatal(failure)) {
            return RetryDecision.GiveUp(failure)
        }

        val maxAttempts = constraints.retries + 1  // retries is ADDITIONAL attempts
        if (attemptNumber >= maxAttempts) {
            return RetryDecision.GiveUp(failure)
        }

        val nextAttempt = attemptNumber + 1
        val backoff = computeBackoff(nextAttempt, constraints.retryBackoffMs, failure)
        return RetryDecision.Retry(
            nextAttemptNumber = nextAttempt,
            delayMs = backoff,
            previousFailure = failure
        )
    }

    /**
     * Failures that should never be retried. Retrying them wastes time and
     * produces misleading telemetry about "flaky" actions.
     */
    private fun isFatal(failure: FailureReason): Boolean = when (failure) {
        is FailureReason.NoExecutor,
        is FailureReason.PermissionDenied,
        is FailureReason.ValidationRejected -> true

        is FailureReason.AccessibilityUnavailable,  // user must enable service
        is FailureReason.Timeout,
        is FailureReason.TargetNotFound,
        is FailureReason.VerificationFailed,
        is FailureReason.SystemError,
        is FailureReason.Unexpected -> false
    }

    private fun computeBackoff(
        nextAttempt: Int,
        baseMs: Long,
        failure: FailureReason
    ): Long {
        // Exponential: base * 2^(nextAttempt - 1)
        // nextAttempt=2 → base * 1 (wait baseMs before 2nd try)
        // nextAttempt=3 → base * 2
        // nextAttempt=4 → base * 4
        val shift = (nextAttempt - 1).coerceIn(0, 10)
        val exponential = baseMs shl shift   // base * 2^shift
        val capped = min(exponential, MAX_BACKOFF_MS)

        // Timeouts get extra backoff — system was busy, give it more room.
        val adjusted = if (failure is FailureReason.Timeout) (capped * 1.5).toLong() else capped

        return applyJitter(adjusted)
    }

    private fun applyJitter(ms: Long): Long {
        val jitterRange = (ms * JITTER_RATIO).toLong()
        if (jitterRange == 0L) return ms
        // Deterministic-ish jitter based on wall clock — fine for backoff purposes,
        // no need for SecureRandom here.
        val jitter = (System.nanoTime() % (2 * jitterRange)) - jitterRange
        return (ms + jitter).coerceAtLeast(0L)
    }
}

/**
 * Outcome of a retry decision.
 *
 * [Retry.previousFailure] carries the failure reason that triggered the retry,
 * so downstream code (checkpoint writer, UI) can show "retrying after timeout"
 * rather than a generic "retrying." Without this field the Retrying state
 * would lose context and every retry would look identical in logs.
 */
sealed class RetryDecision {
    data class Retry(
        val nextAttemptNumber: Int,
        val delayMs: Long,
        val previousFailure: FailureReason
    ) : RetryDecision()

    data class GiveUp(val reason: FailureReason) : RetryDecision()
}