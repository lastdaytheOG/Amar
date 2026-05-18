package com.amar.vault.agent.control

/**
 * Deterministic state machine for agent execution phases.
 */
enum class AgentPhase {
    NAVIGATION,
    INPUT,
    EXECUTION,
    RECOVERY,
    FAILURE
}
