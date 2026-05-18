package com.amar.vault.agent.runtime.state

/**
 * Persistent identity fingerprint for a focused node.
 *
 * AccessibilityNodeInfo references die at the slightest provocation: tree
 * rebuilds, focus changes, scroll, Compose recomposition. Caching them is
 * a known anti-pattern. We instead extract a fingerprint of stable-ish
 * attributes that can re-find an equivalent node after a rebuild.
 *
 * Match priority (used by SemanticResolver in Step 7):
 *   1. resourceId — most stable when present
 *   2. className + contentDesc — stable for IMEs, system widgets
 *   3. className + bounds quadrant — last resort, defeats only minor reflow
 *
 * Bounds are stored as quadrants (top-left, top-right, etc.) not exact pixels
 * because pixel positions drift with animations. Quadrant-stability survives
 * normal layout pass within a window.
 */
data class FocusFingerprint(
    val resourceId: String? = null,
    val className: String? = null,
    val contentDesc: String? = null,
    val textHash: Int? = null,            // hash of placeholder/hint text, NOT user input
    val boundsQuadrant: BoundsQuadrant? = null,
    val isEditable: Boolean = false
) {
    companion object {
        /**
         * Empty fingerprint — represents "no focused element". Useful as a
         * sentinel rather than null where the type system prefers a value.
         */
        val EMPTY = FocusFingerprint()
    }
}

enum class BoundsQuadrant {
    TOP_LEFT, TOP_RIGHT, TOP_CENTER,
    MIDDLE_LEFT, MIDDLE_CENTER, MIDDLE_RIGHT,
    BOTTOM_LEFT, BOTTOM_RIGHT, BOTTOM_CENTER;

    companion object {
        /**
         * Compute quadrant from bounds + screen dimensions.
         * Top band = top 33% of screen, etc.
         */
        fun of(
            centerX: Int,
            centerY: Int,
            screenWidth: Int,
            screenHeight: Int
        ): BoundsQuadrant {
            val xBand = when {
                centerX < screenWidth / 3 -> 0
                centerX < 2 * screenWidth / 3 -> 1
                else -> 2
            }
            val yBand = when {
                centerY < screenHeight / 3 -> 0
                centerY < 2 * screenHeight / 3 -> 1
                else -> 2
            }
            return when (yBand * 3 + xBand) {
                0 -> TOP_LEFT
                1 -> TOP_CENTER
                2 -> TOP_RIGHT
                3 -> MIDDLE_LEFT
                4 -> MIDDLE_CENTER
                5 -> MIDDLE_RIGHT
                6 -> BOTTOM_LEFT
                7 -> BOTTOM_CENTER
                else -> BOTTOM_RIGHT
            }
        }
    }
}