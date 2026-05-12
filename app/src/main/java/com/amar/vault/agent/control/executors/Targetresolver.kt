package com.amar.vault.agent.control.executors

import com.amar.vault.agent.dsl.TargetStrategy
import com.amar.vault.agent.perception.CaptureReason
import com.amar.vault.agent.perception.MatchStrategy
import com.amar.vault.agent.perception.PerceptionService
import com.amar.vault.agent.perception.SnapshotCache
import com.amar.vault.agent.perception.UiElement
import com.amar.vault.agent.perception.UiElementType
import com.amar.vault.agent.perception.UiSnapshot

/**
 * Shared machinery for executors that need to find a UI element by DSL target spec.
 *
 * Resolution policy:
 *   1. If cache has a fresh snapshot, try it first.
 *   2. If not found (or snapshot was stale), force a fresh walk via PerceptionService.
 *   3. Try the requested [TargetStrategy], with AUTO cascading through resource_id →
 *      content_desc → text → any.
 *   4. Return the element if found and interactable, otherwise null.
 */
object TargetResolver {

    data class Result(
        val element: UiElement?,
        val strategiesTried: List<String>,
        val snapshot: UiSnapshot?
    )

    suspend fun resolve(
        target: String,
        strategy: TargetStrategy,
        snapshotCache: SnapshotCache
    ): Result {
        val tried = mutableListOf<String>()

        snapshotCache.currentIfFresh()?.let { snap ->
            val fromCache = attemptResolution(snap, target, strategy, tried)
            if (fromCache != null) return Result(fromCache, tried, snap)
        }

        val service = PerceptionService.get()
            ?: return Result(null, tried + "service_unavailable", null)
        val fresh = service.forceSnapshot(CaptureReason.ON_DEMAND)
        val fromFresh = attemptResolution(fresh, target, strategy, tried)
        return Result(fromFresh, tried, fresh)
    }

    private fun attemptResolution(
        snapshot: UiSnapshot,
        target: String,
        strategy: TargetStrategy,
        tried: MutableList<String>
    ): UiElement? {
        return when (strategy) {
            TargetStrategy.RESOURCE_ID -> {
                tried += "resource_id"
                snapshot.findFirst(target, MatchStrategy.RESOURCE_ID)
            }
            TargetStrategy.TEXT -> {
                tried += "text"
                snapshot.findFirst(target, MatchStrategy.TEXT)
            }
            TargetStrategy.CONTENT_DESC -> {
                tried += "content_desc"
                snapshot.findFirst(target, MatchStrategy.CONTENT_DESC)
            }
            TargetStrategy.AUTO -> {
                tried += "resource_id"
                snapshot.findFirst(target, MatchStrategy.RESOURCE_ID)?.let { return it }
                tried += "content_desc"
                snapshot.findFirst(target, MatchStrategy.CONTENT_DESC)?.let { return it }
                tried += "text"
                snapshot.findFirst(target, MatchStrategy.TEXT)?.let { return it }
                tried += "any"
                snapshot.findFirst(target, MatchStrategy.ANY)
            }
            TargetStrategy.FIRST_CLICKABLE_IN_GRID -> {
                tried += "first_clickable_in_grid"
                resolveFirstClickableInGrid(snapshot, target)
            }
            TargetStrategy.FOCUSED_EDITABLE -> {
                resolveFocusedEditable(snapshot, tried)
            }
        }
    }

    /**
     * "Type into whatever is focused" strategy.
     *
     * Fallback cascade:
     *   1. editable && focused, real bounds       — strict, ideal
     *   2. editable OR INPUT type, in top half    — handles Brave-style URL bars
     *                                                that don't toggle `editable`
     *                                                until clicked
     *   3. any editable on screen with real bounds — last resort
     *
     * `isReal()` rejects ghost / zero-size nodes that occasionally appear in
     * the A11y tree (especially WhatsApp, Brave) and would otherwise be
     * "typed into" silently with no visible effect.
     */
    private fun resolveFocusedEditable(
        snapshot: UiSnapshot,
        tried: MutableList<String>
    ): UiElement? {
        // A node is "real" if it has non-zero bounds. (UiElement doesn't expose
        // a visibleToUser flag; non-zero bounds is the closest proxy we have.)
        val isReal: (UiElement) -> Boolean = { el ->
            el.bounds != null && !el.bounds.isEmpty
        }

        // Tier 1: strict focused-editable
        tried += "focused_editable"
        snapshot.elements.firstOrNull { it.editable && it.focused && isReal(it) }
            ?.let { return it }

        // Tier 2: editable-ish in top half of screen.
        // "Editable-ish" = el.editable=true OR type==INPUT (matches EditText/
        // TextInputLayout/AutoCompleteTextView that haven't toggled editable
        // yet). Spatial filter excludes bottom-anchored comment/chat boxes.
        tried += "editable_top_half"
        val cutoff = estimateBottomHalfCutoff(snapshot)
        snapshot.elements.firstOrNull { el ->
            (el.editable || el.type == UiElementType.INPUT) &&
                    isReal(el) &&
                    (cutoff == null || (el.bounds?.top ?: 0) <= cutoff)
        }?.let { return it }

        // Tier 3: any visible editable-ish element
        tried += "any_visible_editable"
        return snapshot.elements.firstOrNull { el ->
            (el.editable || el.type == UiElementType.INPUT) && isReal(el)
        }
    }

    /**
     * Estimates the y-coordinate dividing top half from bottom half of the
     * screen. Derived from the max element-bottom in the snapshot since we
     * don't store screen height directly. Returns null if snapshot has no
     * bounded elements.
     */
    private fun estimateBottomHalfCutoff(snapshot: UiSnapshot): Int? {
        val maxBottom = snapshot.elements.mapNotNull { it.bounds?.bottom }.maxOrNull()
            ?: return null
        return maxBottom / 2
    }

    /**
     * Pick the first element that looks like a product tile / grid cell.
     */
    private fun resolveFirstClickableInGrid(
        snapshot: UiSnapshot,
        target: String
    ): UiElement? {
        val targetLower = target.trim().lowercase()

        val clickables = snapshot.elements.filter {
            it.clickable && it.enabled
        }
        if (clickables.isEmpty()) return null

        val gridLikeTypes = setOf(
            UiElementType.LIST_ITEM,
            UiElementType.CONTAINER
        )

        // Pass 1: clickable grid cell matching the query
        if (targetLower.isNotBlank()) {
            val matched = clickables.firstOrNull { el ->
                el.type in gridLikeTypes &&
                        (el.text?.lowercase()?.contains(targetLower) == true ||
                                el.contentDesc?.lowercase()?.contains(targetLower) == true)
            }
            if (matched != null) return matched
        }

        // Pass 2: any clickable grid cell
        clickables.firstOrNull { it.type in gridLikeTypes }?.let { return it }

        // Pass 3: first clickable below top 25% of screen
        val topCutoff = estimateTopCutoff(snapshot)
        if (topCutoff != null) {
            clickables.firstOrNull { el ->
                val y = el.bounds?.top ?: Int.MAX_VALUE
                y > topCutoff
            }?.let { return it }
        }

        // Pass 4: last resort — first clickable anything
        return clickables.firstOrNull()
    }

    private fun estimateTopCutoff(snapshot: UiSnapshot): Int? {
        val maxBottom = snapshot.elements
            .mapNotNull { it.bounds?.bottom }
            .maxOrNull() ?: return null
        return maxBottom / 4
    }
}