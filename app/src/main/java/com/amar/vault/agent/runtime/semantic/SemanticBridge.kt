package com.amar.vault.agent.runtime.semantic

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.amar.vault.agent.perception.PerceptionService
import com.amar.vault.agent.runtime.events.AccessibilityEventBus
import com.amar.vault.agent.runtime.events.AgentEvent
import com.amar.vault.agent.runtime.state.BoundsQuadrant
import com.amar.vault.agent.runtime.state.SemanticIdentity
import com.amar.vault.agent.runtime.state.WorldStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listens to bus ViewFocused events and upgrades WorldState's
 * focusedEditableIdentity from a generic Unknown into a typed
 * SemanticIdentity via [SemanticResolver].
 *
 * Wiring:
 *   Started from AmarApplication.onCreate after Step 6 wiring. Idempotent.
 *
 * Important architectural note — why a separate post-reducer step:
 *   The reducer is pure (no I/O). Computing a quadrant requires reading
 *   screen dimensions from the AccessibilityService — that's I/O. So we
 *   can't do this inside AccessibilityReducer.reduce(). Instead, the
 *   reducer sets Unknown as a placeholder, and this Bridge upgrades it
 *   to the typed identity asynchronously.
 *
 *   This introduces a tiny window (a few ms) where focusedEditableIdentity
 *   is Unknown before becoming SearchInput/ChatList/etc. Executors in
 *   Step 8 will either:
 *     (a) wait for a SemanticIdentity that is not Unknown, or
 *     (b) accept Unknown and re-check after a short delay.
 *   Both are acceptable per the architecture's event-sourced model.
 */
@Singleton
class SemanticBridge @Inject constructor(
    private val bus: AccessibilityEventBus,
    private val store: WorldStateStore,
    private val resolver: SemanticResolver
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var subscriberJob: Job? = null

    fun start() {
        if (subscriberJob?.isActive == true) {
            Log.w(TAG, "SEMANTIC_BRIDGE_START_IGNORED already running")
            return
        }
        Log.i(TAG, "SEMANTIC_BRIDGE_START")

        subscriberJob = bus.subscribe<AgentEvent.Accessibility.ViewFocused>()
            .onEach { event -> onFocused(event) }
            .launchIn(scope)
    }

    fun stop() {
        Log.i(TAG, "SEMANTIC_BRIDGE_STOP")
        subscriberJob?.cancel()
        subscriberJob = null
    }

    private fun onFocused(event: AgentEvent.Accessibility.ViewFocused) {
        if (!event.isEditable) {
            // Non-editable focus events don't need semantic upgrade —
            // the reducer already cleared focusedEditableIdentity.
            return
        }

        val pkg = event.packageId ?: return

        // Find the focused node fresh via PerceptionService. Reading from
        // event.source would be ideal but event.source is consumed/recycled
        // by the time this coroutine runs. Use AccessibilityService's
        // findFocus instead.
        val svc = PerceptionService.get() ?: return
        val focusedNode: AccessibilityNodeInfo? = try {
            svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (t: Throwable) {
            Log.w(TAG, "findFocus threw: ${t.message}")
            null
        }
        if (focusedNode == null) {
            Log.d(TAG, "no current focused node; skipping resolve for pkg=$pkg rid=${event.resourceId}")
            return
        }

        try {
            // Read attributes for the resolver.
            val resourceId = try { focusedNode.viewIdResourceName } catch (t: Throwable) { null }
            val contentDesc = try { focusedNode.contentDescription?.toString() } catch (t: Throwable) { null }
            val hint = try { focusedNode.hintText?.toString() } catch (t: Throwable) { null }
            val className = try { focusedNode.className?.toString() } catch (t: Throwable) { null }
            val isEditable = try { focusedNode.isEditable } catch (t: Throwable) { false }

            // Compute quadrant via display metrics.
            val quadrant: BoundsQuadrant? = try {
                val bounds = android.graphics.Rect()
                focusedNode.getBoundsInScreen(bounds)
                if (bounds.isEmpty) null else {
                    val metrics = svc.resources.displayMetrics
                    BoundsQuadrant.of(
                        centerX = bounds.centerX(),
                        centerY = bounds.centerY(),
                        screenWidth = metrics.widthPixels,
                        screenHeight = metrics.heightPixels
                    )
                }
            } catch (t: Throwable) {
                null
            }

            val generationId = com.amar.vault.agent.perception.RootGeneration.current()

            val identity = resolver.resolveFocused(
                packageId = pkg,
                resourceId = resourceId,
                contentDesc = contentDesc,
                hint = hint,
                className = className,
                isEditable = isEditable,
                boundsQuadrant = quadrant,
                generationId = generationId
            )

            // Patch WorldState directly. We can't go through the reducer
            // because reduce() is pure — and this upgrade IS the result of
            // reading mutable state (the node's attributes). Forcing into
            // the store is the correct path.
            store.reduce { current ->
                // Only upgrade if the current identity is still Unknown for
                // this package. If the user has moved on (different package
                // or different identity already set), skip the upgrade —
                // it's stale data from an in-flight resolve.
                val cur = current.focusedEditableIdentity
                if (current.foregroundPackage == pkg &&
                    cur is SemanticIdentity.Unknown &&
                    cur.packageId == pkg
                ) {
                    current.copy(focusedEditableIdentity = identity)
                } else {
                    current
                }
            }

            Log.i(TAG, "SEMANTIC_UPGRADE pkg=$pkg role=${identity::class.simpleName} gen=$generationId")
        } finally {
            try { focusedNode.recycle() } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val TAG = "SemanticBridge"
    }
}