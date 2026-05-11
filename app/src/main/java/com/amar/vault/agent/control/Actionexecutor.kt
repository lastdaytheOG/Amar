package com.amar.vault.agent.control

import com.amar.vault.agent.dsl.AgentAction
import com.amar.vault.agent.dsl.ActionKind

/**
 * Contract every action executor implements.
 *
 * One executor per (action, tier) pair. Tier determines priority when multiple
 * executors can handle the same action — Intent tier beats System API tier beats
 * Accessibility tier (per spec §4).
 *
 * Executors are STATELESS. All per-task state lives in [TaskContext]. This makes
 * them safe to register as singletons and share across concurrent tasks.
 */
interface ActionExecutor {

    /** Which action this executor handles. */
    val handles: ActionKind

    /**
     * Which tier this executor belongs to.
     * Lower number = higher priority = tried first.
     */
    val tier: ExecutorTier

    /**
     * Returns true if this executor is currently capable of running.
     * E.g., Accessibility-tier executors return false if the service isn't bound;
     * SMS executor returns false if SEND_SMS permission isn't granted.
     *
     * The registry uses this to skip unavailable executors before picking a fallback.
     */
    fun isAvailable(): Boolean

    /**
     * Run the action. Suspending — executors should honor cancellation via both:
     *   - Coroutine cancellation (standard kotlinx)
     *   - ctx.isCancelRequested polling (for tight loops over UI trees)
     *
     * Must NOT transition TaskContext state directly — that's the state machine's job.
     * Just return what happened; the ControlLayer interprets the outcome.
     */
    suspend fun execute(action: AgentAction, ctx: TaskContext): ExecutionResult
}

/**
 * Priority tiers per spec §4.
 * Lower ordinal = tried first.
 */
enum class ExecutorTier {
    INTENT,          // Direct app intents, fastest
    SYSTEM_API,      // Android system APIs (SmsManager, TelecomManager)
    ACCESSIBILITY    // Accessibility tree manipulation, fallback only
}

/**
 * What an executor reports back. NOT a state — just a result the ControlLayer
 * translates into a state transition.
 */
sealed class ExecutionResult {

    /** Action ran; verification (if any) still pending. */
    data class Executed(
        val durationMs: Long,
        val resultData: Map<String, String> = emptyMap()
    ) : ExecutionResult()

    /** Action ran AND verified inline (executor self-verified, skip verify step). */
    data class ExecutedAndVerified(
        val durationMs: Long,
        val resultData: Map<String, String> = emptyMap()
    ) : ExecutionResult()

    /** Action failed recoverably — caller may retry. */
    data class Failed(
        val reason: FailureReason,
        val durationMs: Long
    ) : ExecutionResult()

    /**
     * Action failed unrecoverably — DO NOT retry. Examples: permission denied,
     * no executor for action, validation-rejected envelope.
     */
    data class FatalFailure(
        val reason: FailureReason,
        val durationMs: Long
    ) : ExecutionResult()

    /** Cancelled mid-execution. */
    data class Cancelled(val reason: CancellationReason) : ExecutionResult()
}