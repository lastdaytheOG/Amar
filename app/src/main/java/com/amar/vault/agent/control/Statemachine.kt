package com.amar.vault.agent.control

/**
 * Explicit transition table for task lifecycle.
 *
 * Why explicit and not implicit:
 *   An implicit "just set the state" design lets bugs silently skip states
 *   (e.g., executor goes Running → Succeeded without Verifying). That breaks
 *   analytics, checkpoint resume, and debugging. The transition table makes
 *   every legal move auditable.
 *
 * This object is stateless. All state lives in [TaskContext].
 */
object StateMachine {

    /** Returns true if transitioning `from` → `to` is a legal state change. */
    fun canTransition(from: TaskState, to: TaskState): Boolean {
        if (from.taskId != to.taskId) return false       // never cross tasks
        if (from.isTerminal) return false                // terminal is terminal

        return when (from) {
            is TaskState.Pending -> to is TaskState.Running
                    || to is TaskState.Cancelled
                    || to is TaskState.Failed  // validation-rejected before running

            is TaskState.Running -> to is TaskState.Verifying
                    || to is TaskState.Retrying
                    || to is TaskState.Failed
                    || to is TaskState.Cancelled
                    // Running → Succeeded allowed when verify is None (no verification step).
                    || to is TaskState.Succeeded

            is TaskState.Verifying -> to is TaskState.Succeeded
                    || to is TaskState.Retrying
                    || to is TaskState.Failed
                    || to is TaskState.Cancelled

            is TaskState.Retrying -> to is TaskState.Running
                    || to is TaskState.Cancelled
                    || to is TaskState.Failed   // cancelled during backoff

            is TaskState.Succeeded,
            is TaskState.Failed,
            is TaskState.Cancelled -> false                    // unreachable (guarded above)
        }
    }

    /**
     * Produces a human-readable transition name for logging.
     * Used by CheckpointWriter and telemetry.
     */
    fun transitionName(from: TaskState, to: TaskState): String {
        val fromName = from::class.simpleName ?: "?"
        val toName = to::class.simpleName ?: "?"
        return "$fromName→$toName"
    }
}