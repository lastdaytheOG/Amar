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
                // Used by workflows (e.g. Blinkit) to tap the first product tile
                // on a search-results / catalog screen. The `target` parameter is
                // advisory (usually the search query); if no element matches it
                // we fall back to "any clickable container/list-item/card".
                tried += "first_clickable_in_grid"
                resolveFirstClickableInGrid(snapshot, target)
            }
        }
    }

    /**
     * Pick the first element that looks like a product tile / grid cell.
     * Heuristic:
     *   1. Prefer clickable LIST_ITEM / CONTAINER elements whose text or
     *      contentDesc contains [target] (when non-blank).
     *   2. Otherwise, first clickable LIST_ITEM / CONTAINER element anywhere.
     *   3. Otherwise, first clickable element below ~25% of screen height
     *      (search bars live at the top; product tiles live below).
     *   4. Otherwise, first clickable element, period.
     *
     * Returns null if absolutely nothing clickable is on screen.
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

    /**
     * Estimate the pixel cutoff for "below the header region" as ~25% of
     * screen height. Derived from the largest element bounds we see in the
     * snapshot. Returns null if we can't determine it.
     */
    private fun estimateTopCutoff(snapshot: UiSnapshot): Int? {
        val maxBottom = snapshot.elements
            .mapNotNull { it.bounds?.bottom }
            .maxOrNull() ?: return null
        return maxBottom / 4
    }
}