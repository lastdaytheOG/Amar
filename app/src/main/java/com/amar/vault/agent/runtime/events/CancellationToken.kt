package com.amar.vault.agent.runtime.events

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight cooperative cancellation primitive.
 *
 * Issued per-workflow by the orchestrator. Long-running operations check
 * [isCancelled] at safe points and abort early. The bus and reducer use
 * tokens to drop in-flight work when a window changes mid-action.
 *
 * Not a replacement for kotlinx.coroutines CancellableContinuation —
 * cooperates with it. Coroutine cancellation handles structured concurrency;
 * this token handles agent-domain cancellation (window changed, overlay
 * appeared, package shifted) that may not propagate through coroutine scopes.
 */
class CancellationToken(val workflowId: String) {

    private val cancelled = AtomicBoolean(false)
    private val reason = AtomicReference<String?>(null)

    val isCancelled: Boolean get() = cancelled.get()

    val cancelReason: String? get() = reason.get()

    /**
     * Cancel this token with a reason. Idempotent — second call is ignored,
     * preserving the first cancellation's reason.
     */
    fun cancel(reason: String): Boolean {
        if (cancelled.compareAndSet(false, true)) {
            this.reason.set(reason)
            return true
        }
        return false
    }

    /**
     * Throw a CancellationException if cancelled. Call at safe points in
     * long-running work to abort cooperatively.
     */
    @Throws(WorkflowCancelledException::class)
    fun throwIfCancelled() {
        if (cancelled.get()) {
            throw WorkflowCancelledException(workflowId, reason.get() ?: "unknown")
        }
    }

    companion object {
        /** Token that is never cancelled. Useful as a default for non-cancellable work. */
        val NEVER = CancellationToken("never").also { /* never call cancel() */ }
    }
}

class WorkflowCancelledException(
    val workflowId: String,
    val reason: String
) : RuntimeException("Workflow $workflowId cancelled: $reason")