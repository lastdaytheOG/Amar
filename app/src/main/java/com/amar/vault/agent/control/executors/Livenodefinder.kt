package com.amar.vault.agent.control.executors

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.amar.vault.agent.perception.PerceptionService
import com.amar.vault.agent.perception.UiElement

/**
 * Re-resolves a [UiElement] (from a snapshot) back to a live
 * [AccessibilityNodeInfo] in the current tree.
 *
 * Why we can't just keep the original node:
 *   TreeWalker aggressively recycles AccessibilityNodeInfo references to
 *   avoid memory pressure (Android requires this on API <33 and still
 *   benefits from it on newer APIs). By the time an executor runs, the
 *   nodes that produced the snapshot are gone. We have to find them again.
 *
 * Resolution strategy (priority order):
 *   1. resourceId → findAccessibilityNodeInfosByViewId   (most stable)
 *   2. contentDesc → findAccessibilityNodeInfosByText    (cross-matches contentDesc too)
 *   3. text       → findAccessibilityNodeInfosByText
 *   4. path walk  → breadcrumb from root                 (fragile, last resort)
 *
 * Callers should ALWAYS [safeRecycle] the returned node when done. This is
 * the executor's responsibility; the resolver returns ownership to the caller.
 *
 * Extraction rationale:
 *   Previously duplicated across ClickExecutor and TypeTextExecutor. Adding
 *   ScrollExecutor makes three callers — rule-of-three, time to extract.
 *   The `preferEditable` option preserves TypeText's specific preference for
 *   editable candidates without forcing Click/Scroll to care.
 */
object LiveNodeFinder {

    /**
     * @param preferEditable When multiple candidates match, prefer ones whose
     *   isEditable returns true. TypeTextExecutor sets this; Click and Scroll leave it false.
     * @param allowPathFallback Click uses this; Scroll and TypeText disable it
     *   because path-based refind is fragile for their failure modes (tapping
     *   the wrong element by path is worse than failing cleanly).
     */
    fun find(
        service: PerceptionService,
        element: UiElement,
        preferEditable: Boolean = false,
        allowPathFallback: Boolean = true
    ): AccessibilityNodeInfo? {
        val root = try { service.rootInActiveWindow } catch (t: Throwable) { null } ?: return null

        // 1. resourceId
        element.resourceId?.takeIf { it.isNotBlank() }?.let { rid ->
            val nodes = safeFindByViewId(root, rid)
            val best = pickBestMatch(nodes, element, preferEditable)
            for (n in nodes) if (n !== best) safeRecycle(n)
            if (best != null) return best
        }

        // 2. contentDesc (Android's findByText also scans contentDescription)
        element.contentDesc?.takeIf { it.isNotBlank() }?.let { cd ->
            val nodes = safeFindByText(root, cd)
            val best = pickBestMatch(nodes, element, preferEditable)
            for (n in nodes) if (n !== best) safeRecycle(n)
            if (best != null) return best
        }

        // 3. visible text
        element.text?.takeIf { it.isNotBlank() }?.let { txt ->
            val nodes = safeFindByText(root, txt)
            val best = pickBestMatch(nodes, element, preferEditable)
            for (n in nodes) if (n !== best) safeRecycle(n)
            if (best != null) return best
        }

        // 4. hint (useful for empty input fields)
        element.hint?.takeIf { it.isNotBlank() }?.let { hint ->
            val nodes = safeFindByText(root, hint)
            val best = pickBestMatch(nodes, element, preferEditable)
            for (n in nodes) if (n !== best) safeRecycle(n)
            if (best != null) return best
        }

        // 5. path fallback (fragile, opt-in)
        if (allowPathFallback) {
            return findByPath(root, element.path)
        }
        return null
    }

    /**
     * Walk up to [maxHops] ancestors looking for a node satisfying [predicate].
     * Used by ClickExecutor to find a clickable ancestor; by ScrollExecutor to
     * find a scrollable ancestor. Generic keeps both callers simple.
     *
     * Returns the matching node (may be the original). Caller is responsible
     * for recycling any ancestor it didn't originally own.
     */
    fun findAncestor(
        node: AccessibilityNodeInfo,
        maxHops: Int,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < maxHops) {
            val parent = try { current!!.parent } catch (t: Throwable) { null } ?: break
            if (predicate(parent)) return parent
            current = parent
            hops++
        }
        return null
    }

    @Suppress("DEPRECATION")
    fun safeRecycle(n: AccessibilityNodeInfo) {
        // recycle() is deprecated on API 33+ but still required on older.
        // Suppressing intentionally until minSdk > 33.
        try { n.recycle() } catch (t: Throwable) { /* already recycled */ }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun safeFindByViewId(root: AccessibilityNodeInfo, rid: String): List<AccessibilityNodeInfo> =
        try { root.findAccessibilityNodeInfosByViewId(rid) ?: emptyList() } catch (t: Throwable) { emptyList() }

    private fun safeFindByText(root: AccessibilityNodeInfo, text: String): List<AccessibilityNodeInfo> =
        try { root.findAccessibilityNodeInfosByText(text) ?: emptyList() } catch (t: Throwable) { emptyList() }

    /**
     * Score matches against the snapshot element's known bounds and properties.
     * When a selector is ambiguous (e.g., 3 "Send" buttons in a list), we pick
     * the one whose top-left is closest to where the snapshot saw it.
     */
    private fun pickBestMatch(
        candidates: List<AccessibilityNodeInfo>,
        element: UiElement,
        preferEditable: Boolean
    ): AccessibilityNodeInfo? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]

        val pool = if (preferEditable) {
            val editables = candidates.filter { try { it.isEditable } catch (t: Throwable) { false } }
            editables.ifEmpty { candidates }
        } else candidates

        val targetBounds = element.bounds ?: return pool[0]
        return pool.minByOrNull { node ->
            val r = Rect()
            try { node.getBoundsInScreen(r) } catch (t: Throwable) { return@minByOrNull Int.MAX_VALUE }
            val dx = r.left - targetBounds.left
            val dy = r.top - targetBounds.top
            dx * dx + dy * dy
        }
    }

    /**
     * Traverse a breadcrumb path ("0/2/1/3") from the tree root.
     * Returns null if any index is out of bounds or any child fetch fails.
     * Intermediate nodes are recycled along the way; only the terminal node
     * is returned to the caller.
     */
    private fun findByPath(root: AccessibilityNodeInfo, path: String): AccessibilityNodeInfo? {
        if (path.isEmpty()) return root
        val indices = path.split("/").mapNotNull { it.toIntOrNull() }
        if (indices.isEmpty()) return null

        var current: AccessibilityNodeInfo? = root
        val intermediates = mutableListOf<AccessibilityNodeInfo>()
        for (idx in indices) {
            val c = current ?: return null
            val childCount = try { c.childCount } catch (t: Throwable) { return null }
            if (idx < 0 || idx >= childCount) {
                for (n in intermediates) safeRecycle(n)
                return null
            }
            val child = try { c.getChild(idx) } catch (t: Throwable) { null }
            if (child == null) {
                for (n in intermediates) safeRecycle(n)
                return null
            }
            if (current !== root) intermediates += current!!
            current = child
        }
        for (n in intermediates) safeRecycle(n)
        return current
    }
}