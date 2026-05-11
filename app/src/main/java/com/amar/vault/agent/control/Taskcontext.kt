package com.amar.vault.agent.control

import com.amar.vault.agent.dsl.ActionEnvelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-task mutable context passed through the execution pipeline.
 *
 * Responsibilities:
 *   - Own the canonical [state] flow — single source of truth for task lifecycle.
 *   - Hold the cancellation flag (polled by executors in their hot loops).
 *   - Provide a correlation ID for log tracing.
 *   - Expose a completion handle for suspending callers who want to await terminal state.
 *
 * Lifecycle:
 *   - Created by ControlLayer when a task is accepted.
 *   - Lives until terminal state reached + observers drained.
 *   - Not reusable — retry creates new Running state within the same context,
 *     but a cancelled/failed task stays terminal.
 *
 * Threading:
 *   - [state] is StateFlow — safe to observe from any thread/coroutine.
 *   - [cancelRequested] uses AtomicBoolean so executors running in tight loops
 *     can poll without coroutine suspension overhead.
 *   - [transitionTo] should only be called by the StateMachine — executors
 *     report outcomes, they don't mutate state directly.
 */
class TaskContext internal constructor(
    val taskId: String,
    val envelope: ActionEnvelope,
    val createdAt: Long,
    initialState: TaskState.Pending
) {
    private val _state = MutableStateFlow<TaskState>(initialState)

    /** Observable task state. Terminal states are not reset — safe for UI binding. */
    val state: StateFlow<TaskState> = _state.asStateFlow()

    /** Current state snapshot. */
    val current: TaskState
        get() = _state.value

    /**
     * Cooperative cancellation flag. Executors should poll this between steps
     * (especially between accessibility tree walks and retries). Non-blocking read.
     */
    private val _cancelRequested = AtomicBoolean(false)
    val isCancelRequested: Boolean
        get() = _cancelRequested.get()

    /** Coroutine job of the running executor, for structured cancellation. */
    @Volatile
    internal var executorJob: Job? = null

    /** Completes when task reaches terminal state. One-shot. */
    private val completion = CompletableDeferred<TaskState>()

    /** Suspend until the task reaches a terminal state. Returns the terminal state. */
    suspend fun await(): TaskState = completion.await()

    /**
     * Transition to a new state. Called by [StateMachine.advance].
     * Returns true if transition was applied, false if rejected (invalid transition
     * or already terminal).
     */
    internal fun transitionTo(newState: TaskState): Boolean {
        val prev = _state.value
        if (prev.isTerminal) return false
        if (!StateMachine.canTransition(prev, newState)) return false

        _state.value = newState
        if (newState.isTerminal) {
            completion.complete(newState)
        }
        return true
    }

    /**
     * Request cancellation. Idempotent. The state machine will transition
     * to [TaskState.Cancelled] at the next opportunity (or immediately if idle).
     */
    fun requestCancel(reason: CancellationReason = CancellationReason.UserRequested) {
        if (_cancelRequested.compareAndSet(false, true)) {
            executorJob?.cancel()
            // The state machine finalizes the Cancelled transition from whatever
            // state we're in — we don't force it here to keep transitions valid.
            pendingCancellationReason = reason
        }
    }

    @Volatile
    internal var pendingCancellationReason: CancellationReason? = null

    companion object {
        fun create(envelope: ActionEnvelope, now: Long = System.currentTimeMillis()): TaskContext {
            val id = "task-" + UUID.randomUUID().toString().substring(0, 8)
            val initial = TaskState.Pending(
                taskId = id,
                timestamp = now,
                envelope = envelope
            )
            return TaskContext(
                taskId = id,
                envelope = envelope,
                createdAt = now,
                initialState = initial
            )
        }

        /**
         * Create a context for a rejected envelope — one that failed validation
         * and will never execute. The context is created in Pending state (because
         * the state machine requires a valid starting state), then immediately
         * transitioned to [TaskState.Failed]. Callers should treat the returned
         * context as already-terminal: observing [state] will see Failed.
         *
         * We use the [rejectionEnvelope] as a synthetic placeholder. It carries
         * the original action the Brain attempted — useful for retry prompts
         * that want to show "you tried to call open_app with bad params."
         * When no envelope could be parsed at all, [rejectionEnvelope] can be
         * a synthetic envelope with a ReadScreen action — it's never actually
         * executed, so correctness of the placeholder action doesn't matter.
         */
        fun createRejected(
            rejectionEnvelope: ActionEnvelope,
            reason: FailureReason,
            now: Long = System.currentTimeMillis()
        ): TaskContext {
            val ctx = create(rejectionEnvelope, now)
            ctx.transitionTo(
                TaskState.Failed(
                    taskId = ctx.taskId,
                    timestamp = now,
                    attemptsUsed = 0,
                    totalDurationMs = 0,
                    reason = reason
                )
            )
            return ctx
        }
    }
}