package com.amar.vault.agent.runtime.orchestrator

import android.util.Log
import com.amar.vault.agent.runtime.events.AccessibilityEventBus
import com.amar.vault.agent.runtime.events.AgentEvent
import com.amar.vault.agent.runtime.recovery.RecoveryEngine
import com.amar.vault.agent.runtime.state.WorldStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The unifying state machine over agent workflows. See [AgentPhase] for
 * the phase model and [WorkflowContext] for per-workflow state.
 *
 * # Public surface (today)
 *   - [runWorkflow]: take a goal + suspending body, run the body inside the
 *     orchestrator's state machine. The body is the existing executor logic;
 *     the orchestrator wraps it with phase transitions, overlay handling,
 *     and recovery hooks.
 *
 *   - [activeWorkflow]: introspection for tests / diagnostics.
 *
 * # What the orchestrator DOES today
 *   - Creates a WorkflowContext per call.
 *   - Drives the workflow through PLANNING → EXECUTING → VERIFYING.
 *   - Subscribes to OverlayDetected events; if one fires while EXECUTING,
 *     transitions to RECOVERING, calls RecoveryEngine.dismissOnce(),
 *     resumes EXECUTING on success or transitions to ABORTED on failure.
 *   - Detects recovery loops (re-entering RECOVERING within RECOVERY_LOOP_WINDOW_MS
 *     of leaving it) and aborts to prevent infinite cycles.
 *
 * # What the orchestrator does NOT do (deferred to Step 13+)
 *   - Doesn't wrap the existing UiSearchExecutor automatically. Callers
 *     opt into the orchestrator by calling runWorkflow() instead of
 *     executor.execute() directly. The production agent path continues
 *     to use direct executor calls.
 *   - Doesn't enforce phase-specific cancellation tokens yet. That's a
 *     future cleanup; for now any single workflow runs serially.
 *   - Doesn't support parallel workflows. Only one active WorkflowContext
 *     at a time.
 *
 * # Why this isn't wired into production yet
 * The orchestrator changes the semantics of "what happens when an overlay
 * appears mid-injection." Today, overlays cause silent injection failures
 * and the workflow gives up. With the orchestrator wired in, the agent
 * would auto-dismiss "Allow notifications?" popups and retry — which is
 * usually right but occasionally wrong (e.g. permission grant for a
 * sensitive action the user should make manually). Until we verify the
 * orchestrator's behavior on real overlays in real flows, the migration
 * is opt-in only.
 */
@Singleton
class PhaseOrchestrator @Inject constructor(
    private val bus: AccessibilityEventBus,
    private val store: WorldStateStore,
    private val recoveryEngine: RecoveryEngine,
    private val replayRecorder: com.amar.vault.agent.replay.ReplayRecorder
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var subscriberJob: Job? = null

    /**
     * Map of workflowId → live context, so the bus collector can find the
     * active workflow when an OverlayDetected event arrives.
     */
    private val activeWorkflows = ConcurrentHashMap<String, WorkflowContext>()

    fun start() {
        if (subscriberJob?.isActive == true) {
            Log.w(TAG, "PHASE_ORCHESTRATOR_START_IGNORED already running")
            return
        }
        Log.i(TAG, "PHASE_ORCHESTRATOR_START")

        subscriberJob = bus.events
            .onEach { ev ->
                when (ev) {
                    is AgentEvent.Runtime.OverlayDetected -> onOverlayDetected(ev)
                    else -> { /* not interested in non-overlay events */ }
                }
            }
            .launchIn(scope)
    }

    fun stop() {
        Log.i(TAG, "PHASE_ORCHESTRATOR_STOP")
        subscriberJob?.cancel()
        subscriberJob = null
    }

    /**
     * Run a workflow inside the orchestrator's state machine. Phases
     * progress through PLANNING → EXECUTING → VERIFYING. The body
     * function is the executor logic — it runs during EXECUTING.
     *
     * Returns the final phase (COMPLETE or ABORTED) plus the workflow's
     * full history. Caller decides what to do with failures (retry, surface
     * error, etc.) based on the [WorkflowContext.history].
     */
    suspend fun <T> runWorkflow(
        goal: String,
        targetPackage: String,
        executor: suspend (WorkflowContext) -> T
    ): WorkflowResult<T> {
        val ctx = WorkflowContext(goal = goal, targetPackage = targetPackage)
        activeWorkflows[ctx.workflowId] = ctx

        try {
            // PLANNING — short, synchronous; no overlay risk.
            ctx.transitionTo(AgentPhase.PLANNING, PhaseTransitionReason.Normal)
            Log.i(TAG, "PHASE wf=${ctx.workflowId} goal='$goal' pkg=$targetPackage -> PLANNING")

            // Phase 1: replay recording. Tags inferred from packageId so
            // failures can later be clustered by category, not by app.
            replayRecorder.startWorkflow(
                goalStr = goal,
                pkgId = targetPackage,
                tags = inferWorkflowTags(targetPackage)
            )

            // EXECUTING — the body runs here. Overlay events during this
            // phase trigger transitions to RECOVERING via onOverlayDetected.
            ctx.transitionTo(AgentPhase.EXECUTING, PhaseTransitionReason.Normal)
            Log.i(TAG, "PHASE wf=${ctx.workflowId} -> EXECUTING")

            val result: T = executor(ctx)

            // VERIFYING — confirm WorldState reflects success. Cheap check
            // today; could expand with ConfidenceEngine integration later.
            ctx.transitionTo(AgentPhase.VERIFYING, PhaseTransitionReason.Normal)
            Log.i(TAG, "PHASE wf=${ctx.workflowId} -> VERIFYING")

            // No real verification logic yet — we trust the executor's
            // return type for now. Step 13 may integrate ConfidenceEngine
            // here.

            ctx.transitionTo(AgentPhase.COMPLETE, PhaseTransitionReason.Normal)
            Log.i(TAG, "PHASE wf=${ctx.workflowId} -> COMPLETE elapsed=${ctx.elapsedMs()}ms")

            // Phase 4c: inspect the returned value. An ExecutionOutcome.Failed
            // is a regular return (not an exception) but should still be
            // recorded as a Failed workflow with the correct failureClass.
            // We detect via class name to keep this generic-T method neutral
            // about the concrete ExecutionOutcome type.
            val resultClassName = result?.let { it::class.simpleName } ?: ""
            val isFailed = resultClassName == "Failed"
            if (isFailed) {
                // Best-effort extraction of detail string via reflection-free
                // toString — ExecutionOutcome.Failed includes detail in its
                // generated data-class toString output.
                val detailStr = result?.toString() ?: ""
                val failureClass = when {
                    detailStr.contains("app_crash_during_perception") ->
                        com.amar.vault.agent.replay.FailureClass.APP_CRASH_DURING_PERCEPTION
                    detailStr.contains("could not activate search affordance") ->
                        com.amar.vault.agent.replay.FailureClass.NO_SEARCH_AFFORDANCE
                    detailStr.contains("could not type query") ->
                        com.amar.vault.agent.replay.FailureClass.INJECTION_REJECTED
                    detailStr.contains("failed to open app") ->
                        com.amar.vault.agent.replay.FailureClass.APP_LAUNCH_FAILED
                    detailStr.contains("env_recovery_failed") ->
                        com.amar.vault.agent.replay.FailureClass.ENV_RECOVERY_FAILED
                    detailStr.contains("ambiguous_environment") ->
                        com.amar.vault.agent.replay.FailureClass.WRONG_ENVIRONMENT
                    else ->
                        com.amar.vault.agent.replay.FailureClass.UNKNOWN
                }
                Log.w(TAG, "PHASE wf=${ctx.workflowId} executor returned Failed: $failureClass")
                replayRecorder.finishWorkflow(
                    com.amar.vault.agent.replay.ReplayOutcome(
                        kind = "Failed",
                        detail = detailStr.take(200),
                        durationMs = ctx.elapsedMs(),
                        failureClass = failureClass
                    )
                )
            } else {
                replayRecorder.finishWorkflow(
                    com.amar.vault.agent.replay.ReplayOutcome(
                        kind = "Succeeded",
                        durationMs = ctx.elapsedMs()
                    )
                )
            }
            return WorkflowResult.Success(result, ctx)
        } catch (t: Throwable) {
            ctx.transitionTo(
                AgentPhase.ABORTED,
                PhaseTransitionReason.RecoveryFailed(ctx.recoveryEntries)
            )
            Log.w(TAG, "PHASE wf=${ctx.workflowId} -> ABORTED reason=${t.message}")

            replayRecorder.finishWorkflow(
                com.amar.vault.agent.replay.ReplayOutcome(
                    kind = "Failed",
                    detail = t.message,
                    durationMs = ctx.elapsedMs(),
                    failureClass = com.amar.vault.agent.replay.FailureClass.UNKNOWN
                )
            )
            return WorkflowResult.Failure(t, ctx)
        } finally {
            activeWorkflows.remove(ctx.workflowId)
        }
    }

    /**
     * Triggered by the bus when an overlay appears. If any workflow is in
     * EXECUTING, transition it to RECOVERING and attempt dismissal.
     */
    private suspend fun onOverlayDetected(ev: AgentEvent.Runtime.OverlayDetected) {
        // Find an active workflow currently in EXECUTING. If none, ignore
        // — the overlay isn't blocking a workflow we care about.
        val target = activeWorkflows.values.firstOrNull { it.phase == AgentPhase.EXECUTING }
            ?: return

        // Recovery loop guard.
        val sinceLastRecovery = System.currentTimeMillis() - target.lastRecoveryEndedAtMs
        if (target.recoveryEntries > 0 && sinceLastRecovery < RECOVERY_LOOP_WINDOW_MS) {
            Log.w(TAG, "PHASE wf=${target.workflowId} recovery_loop_detected " +
                    "entries=${target.recoveryEntries} sinceLast=${sinceLastRecovery}ms; aborting")
            target.transitionTo(
                AgentPhase.ABORTED,
                PhaseTransitionReason.RecoveryLoop(sinceLastRecovery)
            )
            return
        }

        target.transitionTo(
            AgentPhase.RECOVERING,
            PhaseTransitionReason.OverlayBlocked(ev.overlayType)
        )
        Log.i(TAG, "PHASE wf=${target.workflowId} -> RECOVERING overlay='${ev.overlayType}'")

        // Attempt dismissal with a short timeout. Recovery engine has its
        // own internal logic; we just wait for the outcome.
        val dismissed = withTimeoutOrNull(RECOVERY_TIMEOUT_MS) {
            recoveryEngine.dismissOnce(dismissTarget = null)
        } ?: false

        if (dismissed) {
            target.transitionTo(AgentPhase.EXECUTING, PhaseTransitionReason.Normal)
            Log.i(TAG, "PHASE wf=${target.workflowId} -> EXECUTING (recovered)")
        } else {
            target.transitionTo(
                AgentPhase.ABORTED,
                PhaseTransitionReason.RecoveryFailed(target.recoveryEntries)
            )
            Log.w(TAG, "PHASE wf=${target.workflowId} -> ABORTED (recovery failed)")
        }
    }

    /** Diagnostic: snapshot of active workflows. */
    fun activeWorkflows(): List<WorkflowContext> = activeWorkflows.values.toList()

    /**
     * Infers workflow tags from package id for failure-class clustering.
     * Tags let telemetry group failures by *kind* (compose, chat_input,
     * dynamic_affordance) rather than per-app. Extend as patterns emerge.
     */
    private fun inferWorkflowTags(packageId: String): List<String> {
        val pkg = packageId.lowercase()
        val tags = mutableListOf<String>()
        when {
            pkg.contains("bard") || pkg.contains("googlequicksearchbox") -> {
                tags += listOf("compose", "chat_input", "semantic_transition")
            }
            pkg.contains("chatgpt") || pkg.contains("openai") -> {
                tags += listOf("compose", "chat_input", "post_injection_affordance",
                    "dynamic_affordance_mutation")
            }
            pkg.contains("whatsapp") || pkg.contains("telegram") -> {
                tags += listOf("chat_input", "main_screen_tabs")
            }
            pkg.contains("instagram") || pkg.contains("twitter") -> {
                tags += listOf("social_feed", "search_button")
            }
            pkg.contains("gmail") -> {
                tags += listOf("search_button_shim", "fake_editable_risk")
            }
        }
        return tags
    }

    sealed class WorkflowResult<out T> {
        abstract val context: WorkflowContext

        data class Success<T>(val value: T, override val context: WorkflowContext) : WorkflowResult<T>()
        data class Failure(val cause: Throwable, override val context: WorkflowContext) : WorkflowResult<Nothing>()
    }

    companion object {
        private const val TAG = "PhaseOrchestrator"

        /** How long [recoveryEngine.dismissOnce] is allowed to run. */
        private const val RECOVERY_TIMEOUT_MS = 3_000L

        /** Re-entering RECOVERING within this window = recovery loop, abort. */
        private const val RECOVERY_LOOP_WINDOW_MS = 5_000L
    }
}