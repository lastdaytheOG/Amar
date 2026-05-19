package com.amar.vault.agent.runtime.recovery

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.amar.vault.agent.perception.PerceptionService
import com.amar.vault.agent.runtime.events.AccessibilityEventBus
import com.amar.vault.agent.runtime.events.AgentEvent
import com.amar.vault.agent.runtime.scheduler.ExecutionLane
import com.amar.vault.agent.runtime.scheduler.ExecutionScheduler
import com.amar.vault.agent.runtime.state.WorldStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Responds to overlay detection events by attempting dismissal.
 *
 * # State machine (per the architecture doc):
 *   IDLE → OVERLAY_SEEN → DISMISS_ATTEMPTING → IDLE
 *                                              ↓
 *                                              ABANDON (after max attempts)
 *
 * # When recovery activates:
 *   - OverlayDetected event arrives
 *   - WorldState.inRecovery is set to true
 *   - All other workflows pause (lanes block on RECOVERY ownership)
 *   - Engine attempts up to MAX_DISMISS_ATTEMPTS:
 *       1. Click the negative-action affordance found by OverlayDetector
 *       2. Press GLOBAL_ACTION_BACK
 *   - On success: RecoveryCompleted event, workflows resume
 *   - On failure: WorkflowCancelled with reason=overlay_blocking
 *
 * # Important caveats:
 *   - Recovery NEVER clicks "Allow" on permission dialogs (priority 5 in
 *     OverlayDetector). Permissions must be granted explicitly by the user.
 *   - Recovery does NOT navigate beyond simple dismissal. If a multi-step
 *     dialog (e.g. survey, onboarding) appears, recovery dismisses one
 *     screen and bails — subsequent screens are the user's problem.
 *   - Recovery is opt-in via the executor. The InjectionEngine doesn't
 *     auto-trigger recovery; the orchestrator (Step 12) does.
 *
 * # Today (Step 11):
 *   - Bus subscriptions wired
 *   - dismissOnce() callable manually
 *   - Auto-trigger NOT wired (Step 12 phase orchestrator wires it)
 */
@Singleton
class RecoveryEngine @Inject constructor(
    private val bus: AccessibilityEventBus,
    private val store: WorldStateStore,
    private val scheduler: ExecutionScheduler
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectorJob: Job? = null

    fun start() {
        if (collectorJob?.isActive == true) {
            Log.w(TAG, "RECOVERY_START_IGNORED already running")
            return
        }
        Log.i(TAG, "RECOVERY_START")

        // Subscribe to OverlayDetected. For Step 11 we only LOG the events;
        // Step 12's phase orchestrator decides when to actually trigger
        // dismissal based on what workflow is in flight.
        collectorJob = bus.events
            .onEach { ev ->
                when (ev) {
                    is AgentEvent.Runtime.OverlayDetected -> {
                        Log.i(TAG, "RECOVERY_OBSERVED_OVERLAY type=${ev.overlayType} dismissed=${ev.dismissed}")
                    }
                    else -> { /* not interested */ }
                }
            }
            .launchIn(scope)
    }

    fun stop() {
        Log.i(TAG, "RECOVERY_STOP")
        collectorJob?.cancel()
        collectorJob = null
    }

    /**
     * Attempt to dismiss the current foreground overlay once. Returns true
     * if a dismiss action was performed (mechanical success only — caller
     * verifies the overlay actually closed).
     *
     * Callable manually for now. Step 12 will call this from the phase
     * orchestrator when a workflow detects a blocking overlay mid-flight.
     */
    suspend fun dismissOnce(dismissTarget: String? = null): Boolean {
        // Capture the service reference BEFORE entering the scheduler block.
        // The scheduler.run() lambda's type inference can lose the
        // PerceptionService type. Capturing in the outer scope keeps it.
        val svc: PerceptionService = PerceptionService.get() ?: run {
            Log.w(TAG, "DISMISS_NO_SERVICE")
            return false
        }

        return scheduler.run<Boolean>(ExecutionLane.RECOVERY, "dismissOverlay") {
            val started = System.currentTimeMillis()
            bus.publish(AgentEvent.Runtime.RecoveryStarted(
                cause = "overlay_dismiss",
                fromPhase = "manual"
            ))

            // Strategy 1: click the negative-action affordance if known.
            if (dismissTarget != null) {
                val clicked = clickByText(svc, dismissTarget)
                if (clicked) {
                    Log.i(TAG, "DISMISS_VIA_TARGET text='$dismissTarget' dur=${System.currentTimeMillis() - started}ms")
                    bus.publish(AgentEvent.Runtime.RecoveryCompleted(
                        success = true,
                        resumedPhase = "manual"
                    ))
                    return@run true
                }
            }

            // Strategy 2: press back (covers most modal dialogs).
            val backed = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(300L)
            Log.i(TAG, "DISMISS_VIA_BACK result=$backed dur=${System.currentTimeMillis() - started}ms")
            bus.publish(AgentEvent.Runtime.RecoveryCompleted(
                success = backed,
                resumedPhase = "manual"
            ))
            return@run backed
        }
    }

    private fun clickByText(svc: PerceptionService, text: String): Boolean {
        val root = try { svc.rootInActiveWindow } catch (_: Throwable) { null } ?: return false
        val target = findByText(root, text.lowercase()) ?: return false
        return try { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Throwable) { false }
    }

    private fun findByText(root: AccessibilityNodeInfo, lowerText: String): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            try {
                val nodeText = (node.text?.toString() ?: "").lowercase()
                val nodeDesc = (node.contentDescription?.toString() ?: "").lowercase()
                if (node.isClickable && (nodeText == lowerText || nodeDesc == lowerText)) {
                    return node
                }
            } catch (_: Throwable) {}
            val count = try { node.childCount } catch (_: Throwable) { 0 }
            for (i in 0 until count) {
                try { node.getChild(i)?.let { stack.addLast(it) } } catch (_: Throwable) {}
            }
        }
        return null
    }

    companion object {
        private const val TAG = "RecoveryEngine"
    }
}