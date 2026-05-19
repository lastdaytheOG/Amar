package com.amar.vault.agent.runtime.recovery

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.amar.vault.agent.perception.PerceptionService
import com.amar.vault.agent.runtime.events.AccessibilityEventBus
import com.amar.vault.agent.runtime.events.AgentEvent
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
 * Detects user-facing overlays that block the agent's workflow.
 *
 * # What counts as an overlay
 * An "overlay" here is any visible window/dialog that:
 *   - Wasn't part of the workflow's plan (not the target app's main UI)
 *   - Demands user attention (permission dialog, rating popup, update prompt)
 *   - Will block input until dismissed
 *
 * Common cases:
 *   - System permission dialogs (com.google.android.permissioncontroller)
 *   - Update-now banners from Play Store
 *   - In-app "rate us / 5 stars" prompts
 *   - Cookie / privacy consent banners (web apps)
 *   - Notification flooding (system_ui notifications)
 *
 * # How detection works
 * The detector subscribes to WindowStateChanged and WindowsChanged events.
 * When a new window appears that is NOT the foreground app's expected
 * package or class, AND it contains dismissable affordances ("OK", "Allow",
 * "Not now", "Close", etc.), the detector publishes an OverlayDetected
 * event to the bus.
 *
 * Other layers (RecoveryEngine, OverlayManager — Step 13) act on the event.
 *
 * # What this DOESN'T do
 * The detector observes only. It doesn't dismiss overlays itself. That's
 * the responsibility of the RecoveryEngine. Separation of concerns: this
 * file is "see"; recovery engine is "act".
 */
@Singleton
class OverlayDetector @Inject constructor(
    private val bus: AccessibilityEventBus,
    private val store: WorldStateStore
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectorJob: Job? = null

    /**
     * Packages that are ALWAYS overlays — fire detection regardless of class.
     * These are dialogs / permission UI / system pickers that can never
     * legitimately BE the foreground app.
     */
    private val alwaysOverlayPackages = setOf(
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller"
    )

    /**
     * Packages that MAY be overlays — fire detection only if the windowClass
     * looks dialog-y. Things like systemui handle both notification shades
     * (not overlays) and dialog popups (overlays).
     */
    private val conditionalOverlayPackages = setOf(
        "android",
        "com.android.systemui",
        "com.google.android.gms"
    )

    /**
     * Class name fragments that suggest a dialog or popup window type.
     */
    private val overlayClassFragments = listOf(
        "Dialog", "PopupWindow", "AlertDialog", "BottomSheet",
        "Permission", "Consent", "GrantPermissions"
    )

    fun start() {
        if (collectorJob?.isActive == true) {
            Log.w(TAG, "OVERLAY_DETECTOR_START_IGNORED already running")
            return
        }
        Log.i(TAG, "OVERLAY_DETECTOR_START")

        collectorJob = bus.events
            .onEach { ev ->
                when (ev) {
                    is AgentEvent.Accessibility.WindowStateChanged -> checkForOverlay(ev)
                    else -> { /* not interested */ }
                }
            }
            .launchIn(scope)
    }

    fun stop() {
        Log.i(TAG, "OVERLAY_DETECTOR_STOP")
        collectorJob?.cancel()
        collectorJob = null
    }

    private suspend fun checkForOverlay(ev: AgentEvent.Accessibility.WindowStateChanged) {
        val pkg = ev.packageId ?: return
        val cls = ev.windowClass ?: ""

        val looksLikeDialog = overlayClassFragments.any { cls.contains(it, ignoreCase = true) }
        val isAlwaysOverlay = pkg in alwaysOverlayPackages
        val isConditionalOverlay = pkg in conditionalOverlayPackages && looksLikeDialog

        // Diagnostic: log every WindowStateChanged we evaluate so we can tune
        // the heuristic when overlays we expected to catch slip through.
        Log.d(TAG, "EVAL pkg=$pkg cls=$cls always=$isAlwaysOverlay " +
                "conditional=$isConditionalOverlay dialog=$looksLikeDialog")

        if (!isAlwaysOverlay && !isConditionalOverlay && !looksLikeDialog) return

        // Give a tick for the overlay to fully render before scanning.
        delay(150L)

        val svc = PerceptionService.get() ?: return
        val dismissAffordance = findDismissAffordance(svc)

        Log.i(TAG, "OVERLAY_DETECTED pkg=$pkg cls=$cls dismiss='$dismissAffordance'")
        bus.publish(AgentEvent.Runtime.OverlayDetected(
            overlayType = "$pkg/$cls",
            dismissed = false
        ))
        // Stash dismiss affordance for RecoveryEngine to pick up.
        lastDismissAffordance = dismissAffordance
    }

    /**
     * Last suggested dismiss-affordance text seen. RecoveryEngine reads
     * this when it decides to attempt dismissal. Updated each time an
     * overlay is detected; cleared when recovery succeeds.
     */
    @Volatile var lastDismissAffordance: String? = null
        private set

    /**
     * Walk the active root for buttons whose text/desc suggests dismissal.
     * Returns a string identifier the RecoveryEngine can use to click the
     * right button — preferring negative actions ("Not now", "Cancel") for
     * permission dialogs to avoid accidentally granting permissions.
     */
    private fun findDismissAffordance(svc: PerceptionService): String? {
        val root = try { svc.rootInActiveWindow } catch (_: Throwable) { null } ?: return null
        val candidates = mutableListOf<Pair<String, Int>>()  // (text, priority)

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            try {
                val text = node.text?.toString()?.lowercase() ?: ""
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                val combined = "$text $desc"
                if (node.isClickable) {
                    val priority = when {
                        // Negative-action verbs: prefer these.
                        combined.contains("not now") -> 100
                        combined.contains("cancel") -> 90
                        combined.contains("dismiss") -> 85
                        combined.contains("no thanks") -> 80
                        combined.contains("don't allow") || combined.contains("dont allow") -> 75
                        combined.contains("later") -> 70
                        combined.contains("skip") -> 65
                        combined.contains("close") -> 50
                        // Neutral acknowledgments.
                        combined.trim() == "ok" -> 40
                        combined.trim() == "got it" -> 35
                        combined.contains("continue") -> 20
                        // Permission grant — last resort, we'd rather not click it.
                        combined.contains("allow") -> 5
                        else -> 0
                    }
                    if (priority > 0) {
                        candidates.add((node.text?.toString() ?: node.contentDescription?.toString() ?: "") to priority)
                    }
                }
            } catch (_: Throwable) {}
            val count = try { node.childCount } catch (_: Throwable) { 0 }
            for (i in 0 until count) {
                try { node.getChild(i)?.let { stack.addLast(it) } } catch (_: Throwable) {}
            }
        }
        return candidates.maxByOrNull { it.second }?.first
    }

    companion object {
        private const val TAG = "OverlayDetector"
    }
}