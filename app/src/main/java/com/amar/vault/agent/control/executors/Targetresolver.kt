package com.amar.vault.agent.control.executors

import com.amar.vault.agent.AgentStateHolder
import com.amar.vault.agent.control.AgentPhase
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
                // Semantic matching FIRST — finds elements by their actual
                // accessibility labels, resource IDs, or text. This correctly
                // identifies WhatsApp's search icon (contentDescription="Search")
                // instead of blindly guessing by geometry.
                tried += "resource_id"
                snapshot.findFirst(target, MatchStrategy.RESOURCE_ID)?.let { return it }
                tried += "content_desc"
                snapshot.findFirst(target, MatchStrategy.CONTENT_DESC)?.let { return it }
                tried += "text"
                snapshot.findFirst(target, MatchStrategy.TEXT)?.let { return it }
                tried += "any"
                snapshot.findFirst(target, MatchStrategy.ANY)?.let { return it }

                // StructuralDNA is a LAST RESORT geometric fallback.
                // It CANNOT distinguish a search icon from a sticker/camera/menu icon.
                // Only use it when all semantic matchers above found nothing.
                if (target.contains("search", ignoreCase = true)) {
                    resolveByStructuralDna(snapshot, tried)?.let { return it }
                }
                null
            }
            TargetStrategy.FIRST_CLICKABLE_IN_GRID -> {
                tried += "first_clickable_in_grid"
                resolveFirstClickableInGrid(snapshot, target)
            }
            TargetStrategy.FOCUSED_EDITABLE -> {
                resolveFocusedEditable(snapshot, tried)
            }
            TargetStrategy.STRUCTURAL_DNA -> {
                resolveByStructuralDna(snapshot, tried)
            }
        }
    }

    /**
     * Phase 2 (Elite Tier): Probabilistic Structural DNA Scoring
     * Instead of strict boolean cutoffs, we score every element on the screen.
     * The highest scoring element that passes a minimum confidence threshold wins.
     */
    private fun resolveByStructuralDna(
        snapshot: UiSnapshot,
        tried: MutableList<String>
    ): UiElement? {
        if (AgentStateHolder.phase == AgentPhase.INPUT) {
            tried += "structural_dna_frozen_in_input"
            return null
        }
        tried += "structural_dna_probabilistic"

        val service = PerceptionService.get() ?: return null
        val density = service.resources.displayMetrics.density
        val screenHeight = service.resources.displayMetrics.heightPixels

        val idealSizePx = 48 * density // Standard touch target
        val top15Percent = screenHeight * 0.15f

        var bestMatch: UiElement? = null
        var highestScore = 0

        // Minimum score required to trigger a click (prevents clicking random noise)
        val CONFIDENCE_THRESHOLD = 70

        for (el in snapshot.elements) {
            var score = 0
            val bounds = el.bounds ?: continue

            // 1. BASE REQUIREMENT: Interactivity (Must have to even consider)
            if (!el.clickable && el.type != UiElementType.BUTTON && el.type != UiElementType.IMAGE) {
                continue
            }

            // 2. TEXT PENALTY / REWARD
            if (el.text.isNullOrBlank()) {
                score += 25 // Search icons usually don't have visible text
            } else {
                score -= 50 // Heavy penalty if it has text (it's likely a standard button, not an icon)
            }

            // 3. GEOMETRY: The "Squareness" Ratio
            val w = bounds.width.toFloat()
            val h = bounds.height.toFloat()
            if (w > 0 && h > 0) {
                val maxDim = Math.max(w, h)
                val minDim = Math.min(w, h)
                val aspectRatio = minDim / maxDim

                // If it's 90%+ square, award points. Icons are almost always square.
                if (aspectRatio > 0.90f) score += 20
                else if (aspectRatio > 0.70f) score += 10
            }

            // 4. PHYSICS: Target Size Proximity
            // Instead of strict bounds, we award points based on how close it is to 48dp
            val sizeDiff = Math.abs(w - idealSizePx)
            when {
                sizeDiff <= (4 * density) -> score += 25 // Perfect size (44-52dp)
                sizeDiff <= (10 * density) -> score += 15 // Acceptable size (38-58dp)
                sizeDiff <= (20 * density) -> score += 5  // Weird size, but possible
            }

            // 5. SPATIAL: Location on Screen
            if (bounds.centerY <= top15Percent) {
                score += 30 // It's in the header, extremely high probability
            }

            // --- THE TOPOLOGICAL UPGRADE (Contextual Awareness) ---
            // Does this element live inside a Toolbar or ActionBar?
            val resId = el.resourceId?.lowercase() ?: ""
            if (resId.contains("actionmenu") || resId.contains("toolbar")) {
                score += 20
            }

            // --- SEMANTIC SIGNALS (v3) ---
            // contentDescription is the most reliable signal for icon identity.
            // WhatsApp's search icon has contentDescription="Search".
            // Sticker/emoji/camera icons have their own descriptions.
            val desc = el.contentDesc?.lowercase() ?: ""

            // BOOST: Element whose contentDesc says "search" — this IS
            // what we're looking for. Massive score boost.
            if (desc.contains("search")) {
                score += 80
            }

            // PENALTY: Elements with non-search semantics. These are common
            // toolbar/bottom-bar icons that score high on geometry but are
            // NOT search. Kill their score to prevent false matches.
            val isNonSearchIcon = desc.contains("sticker") ||
                    desc.contains("emoji") ||
                    desc.contains("camera") ||
                    desc.contains("attach") ||
                    desc.contains("gif") ||
                    desc.contains("photo") ||
                    desc.contains("voice") ||
                    desc.contains("video") ||
                    desc.contains("call") ||
                    desc.contains("menu") ||
                    desc.contains("more option") ||
                    desc.contains("new chat") ||
                    desc.contains("community") ||
                    desc.contains("status") ||
                    desc.contains("channel") ||
                    resId.contains("sticker") ||
                    resId.contains("emoji") ||
                    resId.contains("camera") ||
                    resId.contains("attach") ||
                    resId.contains("fab")  // Floating action buttons

            if (isNonSearchIcon) {
                score -= 200 // Kill score — guaranteed to never win
            }

            // Track the winner
            if (score > highestScore && score >= CONFIDENCE_THRESHOLD) {
                highestScore = score
                bestMatch = el
            }
        }

        if (bestMatch != null) {
            android.util.Log.i("TargetResolver",
                "DNA Match: score=$highestScore " +
                        "contentDesc='${bestMatch?.contentDesc}' " +
                        "resId='${bestMatch?.resourceId}' " +
                        "bounds=${bestMatch?.bounds} " +
                        "type=${bestMatch?.type}"
            )
        }

        return bestMatch
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
        if (AgentStateHolder.phase == AgentPhase.INPUT) {
            return null
        }
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