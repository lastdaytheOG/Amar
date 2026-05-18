package com.amar.vault.agent.runtime.cancellation

import android.util.Log
import com.amar.vault.agent.runtime.events.AccessibilityEventBus
import com.amar.vault.agent.runtime.events.AgentEvent
import com.amar.vault.agent.runtime.events.CancellationToken
import com.amar.vault.agent.runtime.state.WorldStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the lifecycle of all in-flight [WorkflowToken]s.
 *
 * Responsibilities:
 *   - Mint new workflow tokens via [open].
 *   - Watch the bus and WorldStateStore for cancellation triggers.
 *   - Cancel workflows whose policy says they should abort on the trigger.
 *   - Schedule per-workflow timeout cancellations.
 *   - Publish [AgentEvent.Runtime.WorkflowCancelled] events when cancelling.
 *
 * Producers:
 *   Today, only [open] / [explicitCancel] / [start] are called. After Step 12
 *   (Recovery Engine), overlay detection will trigger cancellations too.
 *
 * Wiring:
 *   The Application class calls [start] once at boot to enable the auto-cancel
 *   subscribers. Without [start], tokens still work but auto-cancellation on
 *   package change / overlay is disabled.
 *
 *   We DO NOT call start() from AmarApplication yet — keep the live system
 *   identical to Step 4 until executors need it (Step 8). Add the start()
 *   call at that time.
 */
@Singleton
class CancellationManager @Inject constructor(
    private val bus: AccessibilityEventBus,
    private val store: WorldStateStore
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val active = ConcurrentHashMap<String, WorkflowToken>()
    private var watcherJob: Job? = null

    /**
     * Start watching the bus and store for auto-cancellation triggers.
     * Idempotent. Production app calls this in Step 8 wiring; tests may
     * leave it off to drive cancellation manually.
     */
    fun start() {
        if (watcherJob?.isActive == true) {
            Log.w(TAG, "CANCEL_MANAGER_START_IGNORED already running")
            return
        }
        Log.i(TAG, "CANCEL_MANAGER_START")

        // Watch foreground package changes.
        val pkgJob = store.state
            .map { it.foregroundPackage }
            .distinctUntilChanged()
            .onEach { newPkg -> onForegroundPackageChanged(newPkg) }
            .launchIn(scope)

        // Watch overlay events.
        val overlayJob = bus.subscribe<AgentEvent.Runtime.OverlayDetected>()
            .onEach { event -> onOverlayDetected(event) }
            .launchIn(scope)

        watcherJob = scope.launch {
            // Compose two jobs; cancel both when watcherJob cancels.
            try {
                pkgJob.join()
            } finally {
                overlayJob.cancel()
            }
        }
    }

    /**
     * Stop watching. Tests/debug. Production runs forever.
     */
    fun stop() {
        Log.i(TAG, "CANCEL_MANAGER_STOP")
        watcherJob?.cancel()
        watcherJob = null
    }

    /**
     * Mint a new workflow token. Caller uses the returned token to do work
     * and MUST call [close] when done (success or failure) to free resources.
     *
     * If [policy.cancelOnTimeout] is true, a timeout-based auto-cancel
     * coroutine is scheduled on the manager's scope.
     */
    fun open(
        goal: String,
        expectedPackage: String?,
        policy: CancellationPolicy = CancellationPolicy.STRICT
    ): WorkflowToken {
        val id = "wf-${UUID.randomUUID().toString().take(8)}"
        val cancellation = CancellationToken(id)
        val token = WorkflowToken(
            workflowId = id,
            goal = goal,
            startedAtMillis = System.currentTimeMillis(),
            expectedPackage = expectedPackage,
            policy = policy,
            cancellationToken = cancellation
        )
        active[id] = token
        Log.i(TAG, "WORKFLOW_OPEN $id goal='$goal' pkg=$expectedPackage policy=$policy")

        if (policy.cancelOnTimeout) {
            scope.launch {
                delay(policy.timeoutMs)
                val abort = ExecutionAbort.Timeout(
                    workflowId = id,
                    atMillis = System.currentTimeMillis(),
                    elapsedMs = System.currentTimeMillis() - token.startedAtMillis,
                    limitMs = policy.timeoutMs
                )
                cancel(id, abort)
            }
        }

        return token
    }

    /**
     * Close a workflow token. Removes it from the active set. Idempotent.
     */
    fun close(workflowId: String) {
        val removed = active.remove(workflowId)
        if (removed != null) {
            Log.i(TAG, "WORKFLOW_CLOSE $workflowId held=${System.currentTimeMillis() - removed.startedAtMillis}ms")
        }
    }

    /**
     * Explicitly cancel a workflow.
     */
    fun explicitCancel(workflowId: String, reason: String) {
        val abort = ExecutionAbort.ExplicitCancel(
            workflowId = workflowId,
            atMillis = System.currentTimeMillis(),
            reason = reason
        )
        cancel(workflowId, abort)
    }

    /**
     * Internal: cancel a workflow with a typed abort cause. Publishes a
     * WorkflowCancelled event and flips the underlying cooperative token.
     */
    private fun cancel(workflowId: String, abort: ExecutionAbort) {
        val token = active[workflowId] ?: return  // already closed
        if (token.isCancelled) return  // already cancelled
        val cause = abortToString(abort)
        token.cancellationToken.cancel(cause)
        bus.publish(
            AgentEvent.Runtime.WorkflowCancelled(
                workflowId = workflowId,
                cause = cause
            )
        )
        Log.w(TAG, "WORKFLOW_CANCEL $workflowId cause=$cause")
    }

    /**
     * For diagnostics: list all currently active workflows.
     */
    fun activeWorkflows(): List<WorkflowToken> = active.values.toList()

    // ----------------------------------------------------------------
    // Auto-cancel triggers
    // ----------------------------------------------------------------

    private fun onForegroundPackageChanged(newPkg: String?) {
        val toCancel = active.values.filter { t ->
            t.policy.cancelOnPackageChange &&
                    t.expectedPackage != null &&
                    newPkg != null &&
                    newPkg != t.expectedPackage &&
                    !t.isCancelled
        }
        toCancel.forEach { t ->
            cancel(
                t.workflowId,
                ExecutionAbort.PackageChanged(
                    workflowId = t.workflowId,
                    atMillis = System.currentTimeMillis(),
                    expectedPackage = t.expectedPackage,
                    observedPackage = newPkg
                )
            )
        }
    }

    private fun onOverlayDetected(event: AgentEvent.Runtime.OverlayDetected) {
        val toCancel = active.values.filter { t ->
            t.policy.cancelOnOverlay && !t.isCancelled
        }
        toCancel.forEach { t ->
            cancel(
                t.workflowId,
                ExecutionAbort.OverlayInterruption(
                    workflowId = t.workflowId,
                    atMillis = System.currentTimeMillis(),
                    overlayType = event.overlayType
                )
            )
        }
    }

    private fun abortToString(abort: ExecutionAbort): String = when (abort) {
        is ExecutionAbort.PackageChanged ->
            "PackageChanged expected=${abort.expectedPackage} observed=${abort.observedPackage}"
        is ExecutionAbort.OverlayInterruption ->
            "OverlayInterruption type=${abort.overlayType}"
        is ExecutionAbort.Timeout ->
            "Timeout elapsed=${abort.elapsedMs}ms limit=${abort.limitMs}ms"
        is ExecutionAbort.Preempted ->
            "Preempted by=${abort.byWorkflowId}"
        is ExecutionAbort.ExplicitCancel ->
            "ExplicitCancel reason=${abort.reason}"
        is ExecutionAbort.Unknown ->
            "Unknown ${abort.description}"
    }

    companion object {
        private const val TAG = "CancellationManager"
    }
}