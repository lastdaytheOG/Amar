package com.amar.vault.agent.perception

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Maps raw [AccessibilityNodeInfo] to a semantic [UiElementType].
 *
 * Classification strategy (in priority order):
 *   1. Explicit role info (API 30+) via `roleDescription` / class name hints.
 *   2. Accessibility flags (editable, checkable, focusable, scrollable).
 *   3. Class name heuristics (CheckBox, Switch, Button, etc.).
 *   4. Clickable-but-otherwise-unclassified → CONTAINER.
 *   5. Has text but nothing else → TEXT.
 *
 * This is intentionally conservative. A misclassified button-as-container
 * still works (both clickable). A misclassified text-as-button is harmless
 * (planner tries to click, fails, moves on). We avoid over-classifying.
 *
 * IMPORTANT: All AccessibilityNodeInfo accesses are guarded — Android can
 * silently invalidate nodes if the window changes mid-walk. We treat any
 * exception as "couldn't classify, return UNKNOWN". The alternative is
 * crashes in production.
 */
object ElementClassifier {

    fun classify(node: AccessibilityNodeInfo): UiElementType = try {
        val className = node.className?.toString().orEmpty()

        when {
            // --- Explicit widget classes (most reliable) ---
            className.contains("CheckBox", ignoreCase = true)    -> UiElementType.CHECKBOX
            className.contains("RadioButton", ignoreCase = true) -> UiElementType.RADIO
            className.contains("Switch", ignoreCase = true)      -> UiElementType.SWITCH
            className.endsWith("ImageButton")                    -> UiElementType.BUTTON
            className.endsWith("Button")                         -> UiElementType.BUTTON
            className.endsWith("ImageView")                      -> UiElementType.IMAGE

            // --- Editable surfaces ---
            node.isEditable                                      -> UiElementType.INPUT
            className.endsWith("EditText")                       -> UiElementType.INPUT

            // --- List/scroll containers ---
            className.contains("RecyclerView")                   -> UiElementType.LIST
            className.endsWith("ListView")                       -> UiElementType.LIST
            className.endsWith("GridView")                       -> UiElementType.LIST
            node.isScrollable                                    -> UiElementType.SCROLL_VIEW

            // --- Tabs ---
            className.contains("Tab") && node.isClickable        -> UiElementType.TAB

            // --- TextViews and general text ---
            className.endsWith("TextView")                       -> UiElementType.TEXT

            // --- Clickable container fallback (cards, custom views) ---
            node.isClickable                                     -> UiElementType.CONTAINER

            // --- Has visible text but not interactive ---
            !node.text.isNullOrBlank()                           -> UiElementType.TEXT

            else -> UiElementType.UNKNOWN
        }
    } catch (t: Throwable) {
        // Node recycled/invalid mid-walk; don't crash, mark as unknown.
        UiElementType.UNKNOWN
    }

    /**
     * Is this element worth including in a snapshot at Standard depth?
     *
     * We include: interactables, containers that are clickable, inputs, scrollables,
     * lists/list items, and nodes with non-trivial visible text. We exclude: decorative
     * layouts with no text and no interactivity (they inflate snapshot size without
     * adding signal for the planner).
     */
    fun isNoteworthy(
        node: AccessibilityNodeInfo,
        type: UiElementType,
        hasVisibleText: Boolean
    ): Boolean = try {
        when (type) {
            UiElementType.BUTTON,
            UiElementType.INPUT,
            UiElementType.CHECKBOX,
            UiElementType.RADIO,
            UiElementType.SWITCH,
            UiElementType.SCROLL_VIEW,
            UiElementType.LIST,
            UiElementType.LIST_ITEM,
            UiElementType.TAB,
            UiElementType.LINK,
            UiElementType.CONTAINER -> true

            UiElementType.IMAGE -> !node.contentDescription.isNullOrBlank()

            UiElementType.TEXT -> hasVisibleText

            UiElementType.UNKNOWN -> false
        }
    } catch (t: Throwable) {
        false
    }
}