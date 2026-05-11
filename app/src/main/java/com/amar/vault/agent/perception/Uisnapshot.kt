package com.amar.vault.agent.perception

import kotlinx.serialization.Serializable

/**
 * A point-in-time snapshot of the visible UI.
 *
 * Lifecycle:
 *   - Produced by [PerceptionService] on window change events (and on-demand
 *     forced walks from executors).
 *   - Served from cache between invalidations.
 *   - Consumed by:
 *       * Executors (target resolution, coordinate fallback)
 *       * VerificationEngine (ui_contains / app_opened checks)
 *       * Eventually: the Brain as context for reflection/replanning
 *
 * Immutability:
 *   Deliberate — a snapshot represents "what the UI looked like AT capturedAt".
 *   Never mutate after creation. New UI ⇒ new snapshot.
 *
 * Size discipline:
 *   At Standard depth on typical apps this produces 20-200 elements. We cap
 *   the element list at [MAX_ELEMENTS] to prevent pathological cases (infinite
 *   RecyclerViews, WebViews) from blowing memory.
 */
@Serializable
data class UiSnapshot(
    /** Foreground package at capture time. May be null during transitions. */
    val packageId: String?,

    /** Class name of the foreground activity/window, if known. Useful for Brain context. */
    val windowClass: String?,

    /** System time (ms) when the tree was walked. */
    val capturedAt: Long,

    /** Elements in document order (roughly, depth-first). */
    val elements: List<UiElement>,

    /**
     * True if the walker hit a depth cap or element count cap. When true, consumers
     * should treat the snapshot as an incomplete view — the missing parts might
     * contain the target. Executors can respond by requesting a fresh snapshot
     * with a higher budget via [PerceptionService.forceSnapshot].
     */
    val truncated: Boolean = false,

    /**
     * Reason the snapshot was captured. Helps debug whether we're serving stale
     * cache or walking fresh. Not used for logic — just diagnostics.
     */
    val captureReason: CaptureReason = CaptureReason.ON_DEMAND
) {
    val isEmpty: Boolean get() = elements.isEmpty()
    val size: Int get() = elements.size

    /**
     * Finds the first element matching [query] under the given [strategy].
     * Returns null if nothing matches. Callers should then decide whether to
     * force a fresh snapshot or declare target-not-found.
     *
     * Ordering preference: elements matching earlier in the list win. Since
     * the walker emits in roughly top-to-bottom screen order, this usually
     * matches user intent ("click the first Save button on screen").
     */
    fun findFirst(query: String, strategy: MatchStrategy = MatchStrategy.ANY): UiElement? =
        elements.firstOrNull { it.matches(query, strategy) }

    /** Finds all elements matching [query]. Useful when a selector is ambiguous and we need to resolve with heuristics. */
    fun findAll(query: String, strategy: MatchStrategy = MatchStrategy.ANY): List<UiElement> =
        elements.filter { it.matches(query, strategy) }

    /** Tries each strategy in order. Mirrors TargetStrategy.AUTO from the DSL. */
    fun resolveAuto(query: String): UiElement? =
        findFirst(query, MatchStrategy.RESOURCE_ID)
            ?: findFirst(query, MatchStrategy.CONTENT_DESC)
            ?: findFirst(query, MatchStrategy.TEXT)
            ?: findFirst(query, MatchStrategy.ANY)

    /**
     * Returns true if [query] appears anywhere in the visible text of the snapshot.
     * Used by VerifySpec.UiContains.
     */
    fun containsText(query: String, isRegex: Boolean = false): Boolean {
        if (query.isBlank()) return false
        return if (isRegex) {
            val re = runCatching { Regex(query) }.getOrNull() ?: return false
            elements.any { el ->
                (el.text?.let(re::containsMatchIn) == true) ||
                        (el.contentDesc?.let(re::containsMatchIn) == true)
            }
        } else {
            val q = query.lowercase()
            elements.any { el ->
                (el.text?.lowercase()?.contains(q) == true) ||
                        (el.contentDesc?.lowercase()?.contains(q) == true)
            }
        }
    }

    companion object {
        const val MAX_ELEMENTS = 400

        /** Sentinel for when we have no AccessibilityService binding at all. */
        val EMPTY: UiSnapshot = UiSnapshot(
            packageId = null,
            windowClass = null,
            capturedAt = 0L,
            elements = emptyList(),
            truncated = false,
            captureReason = CaptureReason.UNAVAILABLE
        )
    }
}

/**
 * Why a snapshot was captured.
 * Informational only — writing this to logs made debugging cache invalidation
 * ~10x easier during the vault project, so we're keeping the pattern.
 */
@Serializable
enum class CaptureReason {
    /** Window state/content event fired; we walked eagerly. */
    WINDOW_CHANGE,
    /** Executor requested a fresh walk before acting. */
    ON_DEMAND,
    /** Previous snapshot was too old; refreshed on stale read. */
    STALE_REFRESH,
    /** AccessibilityService not bound or disabled — returned EMPTY. */
    UNAVAILABLE
}