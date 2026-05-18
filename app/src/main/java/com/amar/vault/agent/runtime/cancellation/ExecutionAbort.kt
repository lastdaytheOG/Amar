package com.amar.vault.agent.runtime.cancellation

/**
 * Typed cancellation causes. Helps recovery distinguish "user switched apps"
 * (likely intentional, don't retry) from "overlay interrupted" (transient,
 * dismiss and resume).
 *
 * Subscribers in Step 12 (Recovery Engine) branch on this type to pick the
 * right recovery strategy. Today the type just enriches logs.
 */
sealed class ExecutionAbort {

    abstract val workflowId: String
    abstract val atMillis: Long

    data class PackageChanged(
        override val workflowId: String,
        override val atMillis: Long,
        val expectedPackage: String?,
        val observedPackage: String?
    ) : ExecutionAbort()

    data class OverlayInterruption(
        override val workflowId: String,
        override val atMillis: Long,
        val overlayType: String
    ) : ExecutionAbort()

    data class Timeout(
        override val workflowId: String,
        override val atMillis: Long,
        val elapsedMs: Long,
        val limitMs: Long
    ) : ExecutionAbort()

    data class Preempted(
        override val workflowId: String,
        override val atMillis: Long,
        val byWorkflowId: String
    ) : ExecutionAbort()

    data class ExplicitCancel(
        override val workflowId: String,
        override val atMillis: Long,
        val reason: String
    ) : ExecutionAbort()

    /** Used when the cause is recorded but not classified. */
    data class Unknown(
        override val workflowId: String,
        override val atMillis: Long,
        val description: String
    ) : ExecutionAbort()
}