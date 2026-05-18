package com.amar.vault.agent.state

/**
 * Explicit execution phases. The agent's phase determines which actions
 * are permitted — see [PhaseController.isActionAllowed].
 *
 * Transitions are deterministic and state-driven, NOT time-driven:
 *
 *   NAVIGATION → INPUT       when editable+focused node appears OR keyboard visible
 *   INPUT      → EXECUTION   when text mutation verified
 *   *          → RECOVERY    when unexpected divergence detected
 *   RECOVERY   → INPUT       when last semantic lock restored
 *   *          → FAILURE     terminal: all strategies exhausted
 *
 * Backward transitions (INPUT → NAVIGATION) are FORBIDDEN unless the
 * package itself changed — see PhaseController.canRevert().
 */
enum class AgentPhase {
    /** Looking for / activating an affordance. Clicks, gestures, scrolls allowed. */
    NAVIGATION,

    /** A target editable is locked. Only typing/paste/IME/focus actions allowed. */
    INPUT,

    /** Verified mutation in progress (e.g. submit, post-type validation). */
    EXECUTION,

    /** Unexpected divergence; attempting rollback to last semantic lock. */
    RECOVERY,

    /** Terminal failure. */
    FAILURE
}