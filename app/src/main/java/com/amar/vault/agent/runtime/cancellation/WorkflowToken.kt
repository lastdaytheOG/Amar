package com.amar.vault.agent.runtime.cancellation

import com.amar.vault.agent.runtime.events.CancellationToken

/**
 * Higher-level workflow handle that wraps a [CancellationToken] with workflow
 * metadata.
 *
 * Distinction from CancellationToken:
 *   CancellationToken is the cooperative primitive — a flag that can be polled.
 *   WorkflowToken is the orchestrator's view of a running workflow — it carries
 *   the goal, the start time, and the rules for what kinds of events should
 *   trigger auto-cancellation.
 *
 * Auto-cancellation triggers (enforced by [CancellationManager]):
 *   - Foreground package changed (user switched apps mid-task)
 *   - Critical overlay detected (permission dialog, system popup)
 *   - Higher-priority workflow started
 *   - Timeout exceeded
 *
 * A workflow can opt out of any auto-trigger via [CancellationPolicy].
 */
data class WorkflowToken(
    val workflowId: String,
    val goal: String,
    val startedAtMillis: Long,
    val expectedPackage: String?,
    val policy: CancellationPolicy,
    val cancellationToken: CancellationToken
) {
    val isCancelled: Boolean get() = cancellationToken.isCancelled
    val cancelReason: String? get() = cancellationToken.cancelReason
}

/**
 * Rules for when a workflow should be auto-cancelled.
 *
 * Defaults are conservative — most workflows want all auto-cancel triggers
 * enabled. Some workflows (e.g. background sync that explicitly survives
 * foreground app changes) opt out.
 */
data class CancellationPolicy(
    val cancelOnPackageChange: Boolean = true,
    val cancelOnOverlay: Boolean = true,
    val cancelOnTimeout: Boolean = true,
    val timeoutMs: Long = 60_000L
) {
    companion object {
        /** Standard workflow — abort on any unexpected event. */
        val STRICT = CancellationPolicy()

        /** Background workflow — only timeout cancels. */
        val PERMISSIVE = CancellationPolicy(
            cancelOnPackageChange = false,
            cancelOnOverlay = false,
            cancelOnTimeout = true,
            timeoutMs = 300_000L
        )
    }
}