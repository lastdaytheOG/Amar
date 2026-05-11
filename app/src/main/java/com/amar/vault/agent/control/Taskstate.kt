package com.amar.vault.agent.control

import com.amar.vault.agent.dsl.ActionEnvelope
import com.amar.vault.agent.dsl.ActionKind
import com.amar.vault.agent.dsl.ValidationReason

/**
 * Task lifecycle states. Sealed on purpose — the FSM must be exhaustive
 * and transitions auditable.
 *
 * Valid transition graph (enforced by [StateMachine.canTransition]):
 *
 *   PENDING ──► RUNNING ──► VERIFYING ──► SUCCEEDED
 *      │          │             │             │
 *      │          │             ▼             │
 *      │          │         FAILED (retry)    │
 *      │          │             │             │
 *      │          └─────► RETRYING ──┐        │
 *      │                             │        │
 *      │                             ▼        │
 *      │                          RUNNING ────┘
 *      │
 *      └──────────────────► CANCELLED
 *
 * RETRYING and RUNNING are distinct so the UI / logs can show attempt count
 * without ambiguity. A terminal state (SUCCEEDED, FAILED after retries,
 * CANCELLED) never transitions again.
 *
 * Each state carries its own data. This gives us type-safe access to
 * state-specific fields without casting — `when (state) { is Running -> state.attemptNumber }`.
 */
sealed class TaskState {

    abstract val taskId: String
    abstract val timestamp: Long

    /** Created, awaiting dispatch. */
    data class Pending(
        override val taskId: String,
        override val timestamp: Long,
        val envelope: ActionEnvelope
    ) : TaskState()

    /** Executor is actively running (first attempt or after retry). */
    data class Running(
        override val taskId: String,
        override val timestamp: Long,
        val envelope: ActionEnvelope,
        val attemptNumber: Int,                    // 1-indexed
        val startedAt: Long
    ) : TaskState()

    /** Executor finished; verification is in progress. */
    data class Verifying(
        override val taskId: String,
        override val timestamp: Long,
        val envelope: ActionEnvelope,
        val attemptNumber: Int,
        val executionDurationMs: Long
    ) : TaskState()

    /** Attempt failed; waiting for backoff before next retry. */
    data class Retrying(
        override val taskId: String,
        override val timestamp: Long,
        val envelope: ActionEnvelope,
        val nextAttemptNumber: Int,
        val lastFailure: FailureReason,
        val backoffUntil: Long
    ) : TaskState()

    /** Terminal: action executed and verified successfully. */
    data class Succeeded(
        override val taskId: String,
        override val timestamp: Long,
        val attemptsUsed: Int,
        val totalDurationMs: Long,
        val resultData: Map<String, String> = emptyMap()  // e.g., read_screen output
    ) : TaskState()

    /** Terminal: exhausted retries or unrecoverable error. */
    data class Failed(
        override val taskId: String,
        override val timestamp: Long,
        val attemptsUsed: Int,
        val totalDurationMs: Long,
        val reason: FailureReason
    ) : TaskState()

    /** Terminal: user-cancelled or system-cancelled. */
    data class Cancelled(
        override val taskId: String,
        override val timestamp: Long,
        val reason: CancellationReason
    ) : TaskState()

    val isTerminal: Boolean
        get() = this is Succeeded || this is Failed || this is Cancelled
}

/**
 * Why a task failed. Structured so Shadow Brain's failure analysis (later phase)
 * can aggregate by reason without NL parsing.
 */
sealed class FailureReason {

    /** Validation rejected the envelope before execution. */
    data class ValidationRejected(val reasons: List<ValidationReason>) : FailureReason()

    /** Executor timed out against its configured timeout_ms. */
    data class Timeout(val action: ActionKind, val timeoutMs: Long) : FailureReason()

    /** No executor registered for this action. Config/build-time bug. */
    data class NoExecutor(val action: ActionKind) : FailureReason()

    /** Accessibility service not enabled / not bound. Recoverable by user action. */
    data object AccessibilityUnavailable : FailureReason()

    /** Permission missing (e.g., SEND_SMS, CALL_PHONE). */
    data class PermissionDenied(val permission: String) : FailureReason()

    /** Target element not found after exhausting selector strategies. */
    data class TargetNotFound(val target: String, val strategiesTried: List<String>) : FailureReason()

    /** Verification step failed after execution looked OK. */
    data class VerificationFailed(val detail: String) : FailureReason()

    /** Android system rejected the intent / API call. */
    data class SystemError(val detail: String) : FailureReason()

    /** Anything else. Includes underlying throwable message, truncated. */
    data class Unexpected(val detail: String) : FailureReason()
}

/**
 * Why a task was cancelled. Separate from Failed because cancellation is
 * intentional — shouldn't factor into reliability metrics.
 */
sealed class CancellationReason {
    data object UserRequested : CancellationReason()
    data object SystemShutdown : CancellationReason()

    /** Cancelled because a higher-priority task preempted. */
    data class Preempted(val byTaskId: String) : CancellationReason()

    /** Parent plan was aborted (e.g., earlier step failed and plan doesn't recover). */
    data object PlanAborted : CancellationReason()
}