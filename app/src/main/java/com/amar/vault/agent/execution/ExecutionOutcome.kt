package com.amar.vault.agent.execution

/**
 * Outcome of an execution-layer call.
 *
 * This is distinct from com.amar.vault.agent.control.ExecutionResult
 * (which is the ControlLayer's retry/verify-aware return type). The
 * Execution layer in this architecture wraps the control layer's
 * primitives and produces its own higher-level outcome that the
 * Coordinator (Layer 4) consumes.
 *
 * States:
 *   - Started       : the action was dispatched successfully. Target app /
 *                     activity received control. Does NOT imply the user's
 *                     goal is complete — coordinator must observe state
 *                     afterward if completion needs confirmation.
 *   - NotSupported  : this executor cannot handle the request (e.g. app
 *                     doesn't accept ACTION_SEARCH). Caller should try a
 *                     fallback executor.
 *   - Failed        : attempted but threw / returned error. Treated as
 *                     terminal by this executor; caller can still retry at
 *                     a higher level with different strategy.
 */
sealed class ExecutionOutcome {

    data class Started(
        val packageId: String,
        val durationMs: Long,
        val route: String
    ) : ExecutionOutcome()

    data class NotSupported(
        val packageId: String,
        val detail: String,
        val durationMs: Long
    ) : ExecutionOutcome()

    data class Failed(
        val packageId: String,
        val detail: String,
        val durationMs: Long
    ) : ExecutionOutcome()
}