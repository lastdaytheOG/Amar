package com.amar.vault.agent.perception

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Walks an AccessibilityNodeInfo tree and produces a list of [UiElement]s.
 *
 * "Standard depth" per the user's v1 choice:
 *   - Visits inputs, buttons, text, scrollables, lists, clickable containers.
 *   - Descends up to [DEPTH_LIMIT] levels from the window root.
 *   - Hard cap of [MAX_ELEMENTS] to prevent pathological trees from blowing memory.
 *
 * Depth interpretation:
 *   Depth is measured from the window root returned by the AccessibilityService
 *   (i.e., rootInActiveWindow). Depth 0 = root itself. A depth limit of 2 means
 *   we visit root (0), its children (1), and grandchildren (2), but not deeper.
 *   In practice most actionable widgets on a modern app surface within 8–12 levels.
 *
 *   The user chose "Standard: 2-level depth" — but that's 2 levels WITHIN each
 *   content container, not from window root. Android wraps apps in many layers of
 *   CoordinatorLayout/FrameLayout before content begins. We use a practical depth
 *   limit of [DEPTH_LIMIT] = 16 (generous) combined with the noteworthy filter
 *   from ElementClassifier. This matches the user's intent (include scrollables,
 *   lists, clickable containers) without being tripped up by Android's layout
 *   nesting conventions.
 *
 *   If "strictly 2 levels from root" becomes a requirement, change DEPTH_LIMIT
 *   to 2 and expect to miss most interactive elements.
 *
 * Safety:
 *   AccessibilityNodeInfo has reference-counted backing. The docs require recycling
 *   on API < 33, and even on newer APIs leaking nodes causes memory pressure.
 *   We recycle every child we obtain. The [root] passed IN is NOT recycled here —
 *   the caller (PerceptionService) owns the root's lifecycle.
 */
object TreeWalker {

    /** Max depth from window root. Past this, nodes are skipped. */
    const val DEPTH_LIMIT = 32

    /**
     * Walk the tree and produce a snapshot.
     *
     * @param root           Window root from AccessibilityService.rootInActiveWindow.
     *                       MUST NOT be null — callers check and short-circuit.
     * @param packageId      Foreground package (from AccessibilityEvent.packageName).
     * @param windowClass    Foreground window class, for Brain context.
     * @param captureReason  Why we're walking now; tagged onto the snapshot.
     */
    fun walk(
        root: AccessibilityNodeInfo,
        packageId: String?,
        windowClass: String?,
        captureReason: CaptureReason
    ): UiSnapshot {
        val elements = ArrayList<UiElement>(64)
        val tmpRect = Rect()
        var truncated = false

        // Iterative DFS. Recursive was cleaner to write but blew the stack on
        // pathological ChatGPT-style web views during early testing.
        val frames = ArrayDeque<Frame>().apply { add(Frame(root, 0, "")) }

        while (frames.isNotEmpty()) {
            val frame = frames.removeLast()
            if (elements.size >= UiSnapshot.MAX_ELEMENTS) {
                truncated = true
                break
            }
            if (frame.depth > DEPTH_LIMIT) {
                android.util.Log.i("TreeWalker",
                    "DEPTH_LIMIT_HIT pkg=$packageId depth=${frame.depth} " +
                            "elements_so_far=${elements.size}")
                truncated = true
                continue
            }

            val node = frame.node
            val visible = isVisible(node, tmpRect)

            // Only classify/emit visible nodes. Invisible subtrees still get walked
            // in case Android reports a subtree as invisible at the container level
            // but has visible descendants — this does happen with popup menus.
            if (visible) {
                val element = toElement(node, frame.depth, frame.path, tmpRect)
                if (element != null) {
                    elements += element
                }
            }

            // Enqueue children in REVERSE order so DFS visits leftmost first.
            // Stable-ish visual order when the snapshot is linearized.
            val childCount = safeChildCount(node)
            for (i in childCount - 1 downTo 0) {
                val child = safeGetChild(node, i) ?: continue
                val childPath = if (frame.path.isEmpty()) "$i" else "${frame.path}/$i"
                frames.addLast(Frame(child, frame.depth + 1, childPath))
            }

            // Recycle this node unless it's the root (caller owns it).
            if (frame.node !== root) {
                safeRecycle(frame.node)
            }
        }

        // Drain anything left in the frame stack (e.g., hit MAX_ELEMENTS early).
        while (frames.isNotEmpty()) {
            val f = frames.removeLast()
            if (f.node !== root) safeRecycle(f.node)
        }

        return UiSnapshot(
            packageId = packageId,
            windowClass = windowClass,
            capturedAt = System.currentTimeMillis(),
            elements = elements,
            truncated = truncated,
            captureReason = captureReason
        )
    }

    // -------------------------------------------------------------------------
    // Per-node conversion
    // -------------------------------------------------------------------------

    private fun toElement(
        node: AccessibilityNodeInfo,
        depth: Int,
        path: String,
        tmpRect: Rect
    ): UiElement? = try {
        val text = node.text?.toString()?.take(UiElement.MAX_TEXT_LEN)?.takeIf { it.isNotBlank() }
        val contentDesc = node.contentDescription?.toString()?.take(UiElement.MAX_TEXT_LEN)?.takeIf { it.isNotBlank() }
        val hint = node.hintText?.toString()?.take(UiElement.MAX_TEXT_LEN)?.takeIf { it.isNotBlank() }
        val resourceId = node.viewIdResourceName?.takeIf { it.isNotBlank() }

        val hasVisibleText = !text.isNullOrBlank() || !contentDesc.isNullOrBlank()
        val type = ElementClassifier.classify(node)

        if (!ElementClassifier.isNoteworthy(node, type, hasVisibleText)) return null

        node.getBoundsInScreen(tmpRect)
        val bounds = if (tmpRect.isEmpty) null else UiBounds.from(tmpRect)

        UiElement(
            type = type,
            text = text,
            contentDesc = contentDesc,
            hint = hint,
            resourceId = resourceId,
            bounds = bounds,
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            editable = node.isEditable,
            enabled = node.isEnabled,
            focused = node.isFocused,
            depth = depth,
            path = path
        )
    } catch (t: Throwable) {
        null
    }

    // -------------------------------------------------------------------------
    // Defensive helpers — accessibility APIs can throw under node recycling
    // -------------------------------------------------------------------------

    private fun safeChildCount(node: AccessibilityNodeInfo): Int =
        try { node.childCount } catch (t: Throwable) { 0 }

    private fun safeGetChild(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? =
        try { node.getChild(index) } catch (t: Throwable) { null }

    @Suppress("DEPRECATION")
    private fun safeRecycle(node: AccessibilityNodeInfo) {
        // AccessibilityNodeInfo.recycle() is deprecated on API 33+ but still works
        // and is required on older APIs. Suppressing is the recommended migration path
        // until minSdk > 33.
        try { node.recycle() } catch (t: Throwable) { /* already recycled or released */ }
    }

    /**
     * Visibility gate. v2 (Fix B):
     *
     *   Original logic dropped any node where node.isVisibleToUser == false. That
     *   filter is too aggressive: Android's framework reports isVisibleToUser=false
     *   for nodes that ARE on screen but are clipped, partially covered, or have
     *   a parent flagged as "not important for accessibility." WhatsApp's
     *   `my_search_bar` FrameLayout and the lazy-inflated `search_input` EditText
     *   both fall into this trap (confirmed via uiautomator dump showing the nodes
     *   present, but TreeWalker emitting only 15-16 of WhatsApp's hundreds of nodes).
     *
     *   v2 drops the isVisibleToUser check and trusts on-screen bounds only.
     *   A node with non-empty bounds inside the window is considered visible.
     *   Downstream filters (ElementClassifier.isNoteworthy) still reject junk.
     *
     *   This roughly doubles snapshot size. SnapshotCache MAX_ELEMENTS cap still
     *   applies, so worst case is graceful truncation, not OOM.
     */
    private fun isVisible(node: AccessibilityNodeInfo, tmp: Rect): Boolean = try {
        node.getBoundsInScreen(tmp)
        !tmp.isEmpty
    } catch (t: Throwable) {
        false
    }

    private data class Frame(
        val node: AccessibilityNodeInfo,
        val depth: Int,
        val path: String
    )
}