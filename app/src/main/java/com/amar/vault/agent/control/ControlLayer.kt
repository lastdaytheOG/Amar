package com.amar.vault.agent.control

import com.amar.vault.agent.control.persistence.CheckpointWriter
import com.amar.vault.agent.dsl.ActionEnvelope
import com.amar.vault.agent.dsl.ActionValidator
import com.amar.vault.agent.dsl.AgentAction
import com.amar.vault.agent.dsl.ValidationResult
import com.amar.vault.agent.dsl.VerifySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Entry point of the Control Layer.
 *
 * Responsibilities:
 *   - Validate envelopes (via ActionValidator).
 *   - Dispatch to ExecutorRegistry with 3-tier fallback.
 *   - Apply timeout + retry policy.
 *   - Run verification (if any).
 *   - Checkpoint every state transition.
 *
 * Explicitly NOT responsible for:
 *   - Producing envelopes. That's the Brain's job.
 *   - UI state parsing. That's Perception's job. (Verification queries Perception.)
 *   - Planning. Multi-step plans are composed of single envelopes submitted
 *     sequentially; the AgentCoordinator (later) owns plan orchestration.
 *
 * Threading:
 *   - All public entry points are suspend functions.
 *   - Internal scope runs executors on Dispatchers.Default; IO work (DB, network)
 *     is delegated inside individual executors.
 *
 * Input shapes:
 *   - [submitRaw] — raw JSON from the Brain. Validates + executes.
 *   - [submitEnvelope] — pre-validated envelope. For internal callers (tests,
 *     intent cache) that already have typed envelopes.
 */
class ControlLayer(
    private val registry: ExecutorRegistry,
    private val checkpointWriter: CheckpointWriter,
    private val verificationEngine: VerificationEngineApi,
    private val scope: CoroutineScope,
    private val logger: ControlLayerLogger = ControlLayerLogger.NoOp,
    /**
     * Observers called when a task reaches a terminal state. Exist primarily so
     * the watchlist can ref-count packages without the executor-side pollution
     * of "who calls release and when."
     *
     * Each observer runs on the scope's default dispatcher. Observers should be
     * fast and non-blocking; anything expensive should launch its own coroutine.
     * Exceptions inside an observer are logged and swallowed — an observer
     * failing MUST NOT block other observers or affect the task result the
     * user is observing.
     */
    private val terminalObservers: List<TaskTerminalObserver> = emptyList()
) {

    /**
     * Validate a raw JSON envelope string, then execute if valid.
     *
     * Returns the [TaskContext] immediately so callers can observe state.
     * Invalid envelopes produce a context that goes straight to Failed with
     * ValidationRejected — this keeps the return type uniform for the Brain's
     * retry loop (which wants to observe state flows regardless of outcome).
     */
    suspend fun submitRaw(rawJson: String): TaskContext {
        return when (val result = ActionValidator.validate(rawJson)) {
            is ValidationResult.Valid     -> submitEnvelope(result.envelope)
            is ValidationResult.Degraded  -> {
                logger.warn("Envelope accepted with warnings: ${result.warnings}")
                submitEnvelope(result.envelope)
            }
            is ValidationResult.Invalid   -> buildRejectedContext(rawJson, result)
        }
    }

    /**
     * Execute a typed envelope. Assumes already validated — if you're coming
     * from raw LLM output, use [submitRaw].
     */
    suspend fun submitEnvelope(envelope: ActionEnvelope): TaskContext {
        val ctx = TaskContext.create(envelope)
        checkpointWriter.attach(ctx)

        scope.launch(Dispatchers.Default) {
            runTaskLoop(ctx)
            // After task terminates, fan out to observers. Launched in the same
            // coroutine so we don't fire observers until the task loop has
            // actually set a terminal state.
            dispatchTerminalObservers(ctx)
        }
        return ctx
    }

    private fun dispatchTerminalObservers(ctx: TaskContext) {
        if (terminalObservers.isEmpty()) return
        val terminal = ctx.current
        if (!terminal.isTerminal) return  // guard — should always be true here
        for (observer in terminalObservers) {
            try {
                observer.onTerminal(ctx, terminal)
            } catch (t: Throwable) {
                logger.warn("Terminal observer ${observer::class.simpleName} threw: ${t.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Core execution loop
    // -------------------------------------------------------------------------

    private suspend fun runTaskLoop(ctx: TaskContext) {
        val envelope = ctx.envelope
        val constraints = envelope.effectiveConstraints
        val action = envelope.action
        val kind = action.kind
        val startedAt = System.currentTimeMillis()

        // Capability check: any executors registered at all?
        if (registry.allExecutorsFor(kind).isEmpty()) {
            finalizeFailure(
                ctx,
                FailureReason.NoExecutor(kind),
                attemptsUsed = 0,
                startedAt = startedAt
            )
            return
        }

        var attempt = 1

        while (true) {
            // Honor cancellation before starting any attempt.
            if (ctx.isCancelRequested) {
                finalizeCancellation(ctx)
                return
            }

            val candidates = registry.availableExecutorsFor(kind)
            if (candidates.isEmpty()) {
                // Registered but none available — produce a sharper reason.
                val reason = diagnoseUnavailable(registry.allExecutorsFor(kind))
                finalizeFailure(ctx, reason, attemptsUsed = attempt, startedAt = startedAt)
                return
            }

            // Transition: Pending/Retrying → Running
            val attemptStart = System.currentTimeMillis()
            val running = TaskState.Running(
                taskId = ctx.taskId,
                timestamp = attemptStart,
                envelope = envelope,
                attemptNumber = attempt,
                startedAt = attemptStart
            )
            if (!ctx.transitionTo(running)) {
                // Could happen if cancellation landed mid-transition.
                finalizeCancellation(ctx)
                return
            }

            val result = executeWithFallback(
                action = action,
                candidates = candidates,
                ctx = ctx,
                timeoutMs = constraints.timeoutMs
            )

            when (result) {
                is ExecutionResult.Executed -> {
                    // Run verification before declaring success.
                    val verified = runVerification(ctx, envelope, attempt, result)
                    if (verified) {
                        finalizeSuccess(ctx, attempt, startedAt, result.resultData)
                        return
                    } else {
                        val decision = RetryPolicy.decide(
                            attemptNumber = attempt,
                            constraints = constraints,
                            failure = FailureReason.VerificationFailed("verify step returned false")
                        )
                        if (!applyDecision(ctx, decision, envelope, attempt, startedAt)) return
                        attempt = (decision as RetryDecision.Retry).nextAttemptNumber
                    }
                }

                is ExecutionResult.ExecutedAndVerified -> {
                    finalizeSuccess(ctx, attempt, startedAt, result.resultData)
                    return
                }

                is ExecutionResult.Failed -> {
                    val decision = RetryPolicy.decide(attempt, constraints, result.reason)
                    if (!applyDecision(ctx, decision, envelope, attempt, startedAt)) return
                    attempt = (decision as RetryDecision.Retry).nextAttemptNumber
                }

                is ExecutionResult.FatalFailure -> {
                    finalizeFailure(ctx, result.reason, attempt, startedAt)
                    return
                }

                is ExecutionResult.Cancelled -> {
                    finalizeCancellation(ctx)
                    return
                }
            }
        }
    }

    /**
     * Try executors in tier order until one produces a non-retryable outcome.
     * A Failed result from one executor doesn't end the attempt — we fall back
     * to the next tier. Only after ALL executors fail do we return the last failure.
     */
    private suspend fun executeWithFallback(
        action: AgentAction,
        candidates: List<ActionExecutor>,
        ctx: TaskContext,
        timeoutMs: Long
    ): ExecutionResult {
        var lastFailure: ExecutionResult = ExecutionResult.Failed(
            reason = FailureReason.Unexpected("no executors attempted"),
            durationMs = 0
        )

        for (executor in candidates) {
            if (ctx.isCancelRequested) {
                return ExecutionResult.Cancelled(
                    ctx.pendingCancellationReason ?: CancellationReason.UserRequested
                )
            }

            val started = System.currentTimeMillis()
            val result = try {
                withTimeout(timeoutMs) {
                    executor.execute(action, ctx)
                }
            } catch (e: TimeoutCancellationException) {
                ExecutionResult.Failed(
                    reason = FailureReason.Timeout(action.kind, timeoutMs),
                    durationMs = System.currentTimeMillis() - started
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                ExecutionResult.Failed(
                    reason = FailureReason.Unexpected(e.message?.take(200) ?: e::class.simpleName.orEmpty()),
                    durationMs = System.currentTimeMillis() - started
                )
            }

            when (result) {
                is ExecutionResult.Executed,
                is ExecutionResult.ExecutedAndVerified,
                is ExecutionResult.FatalFailure,
                is ExecutionResult.Cancelled -> return result

                is ExecutionResult.Failed -> {
                    lastFailure = result
                    logger.info("Executor ${executor::class.simpleName} failed (${result.reason}); trying next tier")
                    // continue to next candidate
                }
            }
        }

        return lastFailure
    }

    private suspend fun runVerification(
        ctx: TaskContext,
        envelope: ActionEnvelope,
        attempt: Int,
        exec: ExecutionResult.Executed
    ): Boolean {
        val spec = envelope.effectiveVerify
        if (spec is VerifySpec.None || spec is VerifySpec.Unknown) {
            // Nothing to verify — trust the executor.
            return true
        }

        val verifying = TaskState.Verifying(
            taskId = ctx.taskId,
            timestamp = System.currentTimeMillis(),
            envelope = envelope,
            attemptNumber = attempt,
            executionDurationMs = exec.durationMs
        )
        ctx.transitionTo(verifying)

        return try {
            verificationEngine.verify(spec, ctx)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logger.warn("Verification threw: ${e.message}")
            false
        }
    }

    private suspend fun applyDecision(
        ctx: TaskContext,
        decision: RetryDecision,
        envelope: ActionEnvelope,
        attempt: Int,
        startedAt: Long
    ): Boolean {
        return when (decision) {
            is RetryDecision.GiveUp -> {
                finalizeFailure(ctx, decision.reason, attempt, startedAt)
                false
            }
            is RetryDecision.Retry -> {
                val until = System.currentTimeMillis() + decision.delayMs
                ctx.transitionTo(
                    TaskState.Retrying(
                        taskId = ctx.taskId,
                        timestamp = System.currentTimeMillis(),
                        envelope = envelope,
                        nextAttemptNumber = decision.nextAttemptNumber,
                        lastFailure = decision.previousFailure,
                        backoffUntil = until
                    )
                )
                // Interruptible backoff — short delays + cancel polling.
                val step = 100L
                var remaining = decision.delayMs
                while (remaining > 0) {
                    if (ctx.isCancelRequested) {
                        finalizeCancellation(ctx)
                        return false
                    }
                    val slice = minOf(step, remaining)
                    delay(slice)
                    remaining -= slice
                }
                true
            }
        }
    }

    // -------------------------------------------------------------------------
    // Terminal transitions
    // -------------------------------------------------------------------------

    private fun finalizeSuccess(
        ctx: TaskContext,
        attempts: Int,
        startedAt: Long,
        resultData: Map<String, String>
    ) {
        ctx.transitionTo(
            TaskState.Succeeded(
                taskId = ctx.taskId,
                timestamp = System.currentTimeMillis(),
                attemptsUsed = attempts,
                totalDurationMs = System.currentTimeMillis() - startedAt,
                resultData = resultData
            )
        )
    }

    private fun finalizeFailure(
        ctx: TaskContext,
        reason: FailureReason,
        attemptsUsed: Int,
        startedAt: Long
    ) {
        ctx.transitionTo(
            TaskState.Failed(
                taskId = ctx.taskId,
                timestamp = System.currentTimeMillis(),
                attemptsUsed = attemptsUsed,
                totalDurationMs = System.currentTimeMillis() - startedAt,
                reason = reason
            )
        )
    }

    private fun finalizeCancellation(ctx: TaskContext) {
        ctx.transitionTo(
            TaskState.Cancelled(
                taskId = ctx.taskId,
                timestamp = System.currentTimeMillis(),
                reason = ctx.pendingCancellationReason ?: CancellationReason.UserRequested
            )
        )
    }

    private fun buildRejectedContext(
        rawJson: String,
        result: ValidationResult.Invalid
    ): TaskContext {
        // We couldn't parse a real envelope. Synthesize a placeholder — it never
        // executes, only exists so the returned TaskContext has the uniform shape
        // callers expect (StateFlow<TaskState>, awaitable, etc.).
        //
        // ReadScreen is a zero-arg action so it's safe to construct even though
        // we have no real action to represent. The actual failure reason preserves
        // the validation errors so the Brain's retry loop can correct and resubmit.
        val placeholder = ActionEnvelope(
            action = AgentAction.ReadScreen,
            constraints = null,
            verify = null
        )
        val ctx = TaskContext.createRejected(
            rejectionEnvelope = placeholder,
            reason = FailureReason.ValidationRejected(result.reasons)
        )
        // Also checkpoint the rejection so it's visible in the audit trail alongside
        // real executions. Attach is safe even though the context is already terminal —
        // the writer will record the Pending→Failed transition chain on its first emit.
        checkpointWriter.attach(ctx)
        logger.warn("Envelope rejected: ${result.reasons.joinToString(";") { "${it.code}:${it.message}" }}")
        return ctx
    }

    private fun diagnoseUnavailable(all: List<ActionExecutor>): FailureReason {
        // If all candidates are accessibility-tier, the service probably isn't bound.
        if (all.isNotEmpty() && all.all { it.tier == ExecutorTier.ACCESSIBILITY }) {
            return FailureReason.AccessibilityUnavailable
        }
        // Otherwise fall back to generic NoExecutor — individual executors should
        // expose permission issues via FatalFailure from execute(), not via isAvailable().
        return FailureReason.NoExecutor(all.firstOrNull()?.handles ?: return FailureReason.Unexpected("no_diagnostic"))
    }
}

/**
 * Minimal logging surface. Hilt/Timber bindings can be wired in AgentModule.
 * Default NoOp keeps Control Layer testable without a logging framework on the classpath.
 */
interface ControlLayerLogger {
    fun info(msg: String) {}
    fun warn(msg: String) {}
    fun error(msg: String, t: Throwable? = null) {}

    object NoOp : ControlLayerLogger
}

/**
 * VerificationEngine is implemented in the `reflection` package (later build step).
 * We declare the API here to break the cyclical dependency: ControlLayer depends on
 * an interface it can mock in tests; the real impl in reflection/ depends on
 * Perception.
 */
interface VerificationEngineApi {
    suspend fun verify(spec: VerifySpec, ctx: TaskContext): Boolean
}

/**
 * Notified when a task reaches a terminal state (Succeeded / Failed / Cancelled).
 *
 * Implementations must be fast and exception-tolerant. The ControlLayer swallows
 * observer exceptions and logs them, but slow observers will delay the observer
 * chain for the current task (they run sequentially, not in parallel).
 *
 * Primary v1 implementations:
 *   - [com.amar.vault.agent.control.observers.WatchlistReleaser] — releases
 *     PackageWatchlist refs so the Accessibility service stops watching
 *     packages once no task is targeting them.
 */
interface TaskTerminalObserver {
    fun onTerminal(ctx: TaskContext, terminal: TaskState)
}