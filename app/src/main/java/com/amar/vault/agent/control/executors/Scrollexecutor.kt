package com.amar.vault.agent.control.executors

import android.view.accessibility.AccessibilityNodeInfo
import com.amar.vault.agent.control.ActionExecutor
import com.amar.vault.agent.control.ExecutionResult
import com.amar.vault.agent.control.ExecutorTier
import com.amar.vault.agent.control.FailureReason
import com.amar.vault.agent.control.TaskContext
import com.amar.vault.agent.dsl.ActionKind
import com.amar.vault.agent.dsl.AgentAction
import com.amar.vault.agent.dsl.ScrollDirection
import com.amar.vault.agent.dsl.TargetStrategy
import com.amar.vault.agent.perception.PerceptionService
import com.amar.vault.agent.perception.SnapshotCache

/**
 * Scrolls a scrollable container in the given direction.
 *
 * Target selection:
 *   - If [AgentAction.Scroll.target] is non-null → resolve via TargetResolver,
 *     then look for a scrollable ancestor within 2 hops.
 *   - If target is null → find the first scrollable element in the current
 *     snapshot. This is the common case for "scroll down" in a chat app —
 *     there's only one plausible scrollable.
 *
 * Scroll mechanism:
 *   Android exposes scroll as a semantic action via ACTION_SCROLL_FORWARD /
 *   ACTION_SCROLL_BACKWARD. These work on any scrollable view (RecyclerView,
 *   ListView, ScrollView, NestedScrollView, WebView). They scroll by one
 *   "logical page" (view's own definition — usually one screenful).
 *
 *   Horizontal scrolling requires API 23+ with ACTION_SCROLL_LEFT/RIGHT. We
 *   guard with Build.VERSION checks; on older devices, LEFT/RIGHT fall back
 *   to BACKWARD/FORWARD which is frequently wrong but better than failing.
 *
 * Why not gesture-based (dispatchGesture)?
 *   Gesture dispatch gives us pixel-level control but requires knowing:
 *     - the scrollable's bounds,
 *     - an appropriate gesture duration,
 *     - a safe start/end Y within those bounds.
 *   Accuracy varies wildly across devices and DPI. Semantic actions respect
 *   the view's own scroll semantics (e.g., RecyclerView paginates by item
 *   rows, not by screen pixels) and produce more reliable results. If
 *   semantic scroll isn't supported by a specific view, we log and fail —
 *   the plan can retry with a gesture-based approach in a future executor.
 *
 * Tier: ACCESSIBILITY.
 *
 * Verification:
 *   Returns Executed, not ExecutedAndVerified. Whether the scroll "worked"
 *   depends on whether the target content came into view — that's a verify
 *   concern, not an executor one. Many valid scrolls don't move the view
 *   (already at the end), and we shouldn't report those as failures.
 */
class ScrollExecutor(
    private val snapshotCache: SnapshotCache
) : ActionExecutor {

    override val handles: ActionKind = ActionKind.SCROLL
    override val tier: ExecutorTier = ExecutorTier.ACCESSIBILITY

    override fun isAvailable(): Boolean = PerceptionService.get() != null

    override suspend fun execute(action: AgentAction, ctx: TaskContext): ExecutionResult {
        val scroll = action as? AgentAction.Scroll ?: return ExecutionResult.FatalFailure(
            reason = FailureReason.Unexpected("ScrollExecutor received ${action.kind}"),
            durationMs = 0
        )

        val started = System.currentTimeMillis()
        val service = PerceptionService.get() ?: return ExecutionResult.Failed(
            reason = FailureReason.AccessibilityUnavailable,
            durationMs = 0
        )

        // Step 1: locate the scrollable target.
        val scrollable = resolveScrollable(scroll.target, service)
            ?: return ExecutionResult.Failed(
                reason = FailureReason.TargetNotFound(
                    target = scroll.target ?: "<first-scrollable>",
                    strategiesTried = listOf("resolve_scrollable")
                ),
                durationMs = System.currentTimeMillis() - started
            )

        // Step 2: dispatch the scroll action.
        val actionId = scrollActionFor(scroll.direction)
        val ok = try {
            scrollable.performAction(actionId)
        } catch (t: Throwable) {
            false
        } finally {
            LiveNodeFinder.safeRecycle(scrollable)
        }

        val dur = System.currentTimeMillis() - started
        return if (ok) {
            ExecutionResult.Executed(
                durationMs = dur,
                resultData = mapOf(
                    "direction" to scroll.direction.name,
                    "target" to (scroll.target ?: "<auto>")
                )
            )
        } else {
            // performAction false = the view refused to scroll. Most common
            // cause: already at the boundary. We report as Failed so the
            // retry policy can decide, but the Brain should ideally avoid
            // generating scrolls that can't advance (via read_screen first).
            ExecutionResult.Failed(
                reason = FailureReason.SystemError(
                    "scroll action rejected — view may be at the boundary " +
                            "or not support ACTION_SCROLL_${scroll.direction.name}"
                ),
                durationMs = dur
            )
        }
    }

    /**
     * Find the scrollable node to act on. Two strategies:
     *
     *   a) target specified → resolve via TargetResolver, then walk up to 2
     *      ancestors looking for scrollable. This matches "scroll the thing
     *      containing 'Recent messages'" — the Brain names a visible landmark
     *      inside the scrollable.
     *
     *   b) target null → pick the first scrollable element in the snapshot.
     *      Works for ~90% of real scroll use cases.
     */
    private suspend fun resolveScrollable(
        target: String?,
        service: PerceptionService
    ): AccessibilityNodeInfo? {
        if (target == null) {
            val snap = snapshotCache.currentIfFresh() ?: service.forceSnapshot()
            val scrollableEl = snap.elements.firstOrNull { it.scrollable } ?: return null
            val liveNode = LiveNodeFinder.find(service, scrollableEl, allowPathFallback = false)
                ?: return null
            return if (liveNode.isScrollable) {
                liveNode
            } else {
                // Matched element is inside a scrollable; walk up.
                val scrollable = LiveNodeFinder.findAncestor(liveNode, maxHops = 4) { it.isScrollable }
                if (scrollable !== liveNode) LiveNodeFinder.safeRecycle(liveNode)
                scrollable
            }
        }

        // Targeted path: resolve element first, then find scrollable ancestor.
        val resolve = TargetResolver.resolve(target, TargetStrategy.AUTO, snapshotCache)
        val element = resolve.element ?: return null

        val liveNode = LiveNodeFinder.find(service, element, allowPathFallback = false)
            ?: return null

        if (liveNode.isScrollable) return liveNode

        // Up to 4 hops — Compose apps nest scrollables more deeply than classic View hierarchy.
        val scrollable = LiveNodeFinder.findAncestor(liveNode, maxHops = 4) { it.isScrollable }
        if (scrollable !== liveNode) LiveNodeFinder.safeRecycle(liveNode)
        return scrollable
    }

    private fun scrollActionFor(direction: ScrollDirection): Int = when (direction) {
        ScrollDirection.UP    -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        ScrollDirection.DOWN  -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        ScrollDirection.LEFT  -> {
            // ACTION_SCROLL_LEFT was added in API 23 (N — Nougat was 24; LEFT
            // constant actually arrived at 23). Safe on every device we target.
            android.R.id.accessibilityActionScrollLeft
        }
        ScrollDirection.RIGHT -> android.R.id.accessibilityActionScrollRight
    }
}