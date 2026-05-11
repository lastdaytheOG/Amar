package com.amar.vault.agent.perception

import android.graphics.Rect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single element in a UI snapshot. One [UiElement] roughly corresponds to one
 * interactable or semantically important [android.view.accessibility.AccessibilityNodeInfo].
 *
 * Design notes:
 *   - NOT a 1:1 mirror of AccessibilityNodeInfo. The raw tree is enormous (FrameLayouts,
 *     View containers, decorations). We compress to what's useful for an agent planner:
 *     things to click, fields to type into, scrollables to scroll, and text to read.
 *   - Bounds are included because executor fallbacks sometimes need them (e.g., when
 *     selector matching fails and we need to tap by coordinates — explicitly a last resort).
 *   - `path` is a stable-ish identifier for re-querying the same element across snapshots.
 *     It's a breadcrumb of child indices from the window root, not a resource ID. Use
 *     resourceId if present and stable; path is a fallback for unnamed nodes.
 *
 * Why not use AccessibilityNodeInfo directly?
 *   AccessibilityNodeInfo instances are SHORT-LIVED and must be recycled. Leaking them
 *   into long-running caches causes hard crashes on older Android versions and memory
 *   pressure on all versions. UiElement is a plain data class safe to cache freely.
 */
@Serializable
data class UiElement(
    /** Semantic role — what the agent planner should treat this as. */
    val type: UiElementType,

    /** Visible text, if any. Trimmed and bounded to 200 chars. */
    val text: String? = null,

    /** Content description (accessibility label). Often more stable than visible text. */
    val contentDesc: String? = null,

    /** View hint text (input fields only). */
    val hint: String? = null,

    /**
     * Android resource id in the form "package:id/name", if present.
     * Most stable selector across runs. Absent for dynamically-generated views.
     */
    val resourceId: String? = null,

    /** Bounds in screen coordinates. `null` if Android reported none (rare). */
    val bounds: UiBounds? = null,

    /** True if Android reports this node can be clicked. */
    val clickable: Boolean = false,

    /** True if scrollable (applies to scroll_view, list_view, recycler, etc.). */
    val scrollable: Boolean = false,

    /** True if this is an input field the user can type into. */
    val editable: Boolean = false,

    /** True if currently in enabled state. Disabled buttons won't respond to clicks. */
    val enabled: Boolean = true,

    /** True if currently focused. */
    val focused: Boolean = false,

    /**
     * Depth from window root (0 = root). We cap traversal at DEPTH_LIMIT in
     * TreeWalker; depth > limit means the element exists in the tree but we
     * didn't descend into it. Should never happen in a well-formed snapshot
     * because we only emit elements we DID descend into.
     */
    val depth: Int,

    /**
     * Breadcrumb of child indices from window root, e.g. "0/2/1/3".
     * Lets us re-resolve the same logical element across snapshots when no
     * resourceId/text is available. Best-effort — layout changes invalidate it.
     */
    val path: String
) {
    /**
     * Returns true if this element could plausibly be the target of
     * a selector matching [query]. Matching is OR across text/contentDesc/hint/resourceId.
     * Case-insensitive substring match. Executors can layer stricter logic on top.
     */
    fun matches(query: String, strategy: MatchStrategy = MatchStrategy.ANY): Boolean {
        if (query.isBlank()) return false
        val q = query.trim().lowercase()
        return when (strategy) {
            MatchStrategy.ANY -> text.matchesLower(q)
                    || contentDesc.matchesLower(q)
                    || hint.matchesLower(q)
                    || resourceId.matchesLower(q)
            MatchStrategy.TEXT         -> text.matchesLower(q)
            MatchStrategy.CONTENT_DESC -> contentDesc.matchesLower(q)
            MatchStrategy.RESOURCE_ID  -> resourceId.matchesLower(q)
        }
    }

    private fun String?.matchesLower(q: String): Boolean =
        this != null && this.lowercase().contains(q)

    companion object {
        const val MAX_TEXT_LEN = 200
    }
}

/**
 * Serializable bounds. Converts to/from android.graphics.Rect via companion helpers.
 * Kept separate from Rect so UiElement can be serialized without an Android dependency
 * in unit tests.
 */
@Serializable
data class UiBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    companion object {
        fun from(rect: Rect): UiBounds =
            UiBounds(rect.left, rect.top, rect.right, rect.bottom)
    }
}

/**
 * Semantic role of a UI element. Derived from AccessibilityNodeInfo flags +
 * className heuristics by [ElementClassifier].
 *
 * Stable numeric ordering is NOT required — these are wire-format names, not
 * checkpoint codes.
 */
@Serializable
enum class UiElementType {
    @SerialName("button")       BUTTON,
    @SerialName("input")        INPUT,
    @SerialName("text")         TEXT,
    @SerialName("image")        IMAGE,
    @SerialName("checkbox")     CHECKBOX,
    @SerialName("radio")        RADIO,
    @SerialName("switch")       SWITCH,
    @SerialName("scroll_view")  SCROLL_VIEW,
    @SerialName("list")         LIST,
    @SerialName("list_item")    LIST_ITEM,
    @SerialName("tab")          TAB,
    @SerialName("link")         LINK,
    /** Clickable but unclassified container — likely a card or custom view. */
    @SerialName("container")    CONTAINER,
    /** Fallback — rendered element we couldn't classify. Consumers should usually skip. */
    @SerialName("unknown")      UNKNOWN
}

enum class MatchStrategy {
    ANY, TEXT, CONTENT_DESC, RESOURCE_ID
}