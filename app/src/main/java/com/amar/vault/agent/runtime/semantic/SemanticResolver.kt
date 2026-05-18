package com.amar.vault.agent.runtime.semantic

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.amar.vault.agent.runtime.state.BoundsQuadrant
import com.amar.vault.agent.runtime.state.FocusFingerprint
import com.amar.vault.agent.runtime.state.SemanticIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Converts raw accessibility data into typed [SemanticIdentity] instances.
 *
 * Strategy:
 *   The resolver inspects multiple signals from the focused node:
 *     1. resource-id (most reliable when present)
 *     2. content-desc / hint text (semantic labels)
 *     3. class name + editable flag (structural type)
 *     4. screen position (BoundsQuadrant)
 *
 *   Each role has a classifier function below. Classifiers return non-null
 *   when their pattern matches. The resolver tries each role in priority
 *   order and returns the first match.
 *
 * Priority order (today):
 *   SearchInput > PrimaryAction > ChatList > Unknown
 *
 *   Each classifier is conservative — false-negatives are preferred over
 *   false-positives. Misclassifying a random EditText as SearchInput would
 *   cause the Injection Engine to type into the wrong field.
 *
 * Per-app heuristics (later steps):
 *   Step 9 (Framework Adapters) layers app-specific patterns on top of these
 *   generic ones. For example, the WhatsAppAdapter overrides SearchInput to
 *   require `content-desc="Ask Meta AI or Search"` instead of the generic
 *   "search" substring check.
 *
 *   Today, generic patterns only. Sufficient for WhatsApp + ChatGPT + most
 *   stock-Android apps.
 */
@Singleton
class SemanticResolver @Inject constructor(
    private val registry: IdentityRegistry
) {

    /**
     * Resolve a focused-node observation into a SemanticIdentity.
     * Returns null if no role matches (the caller should fall back to Unknown).
     *
     * @param packageId      foreground package at observation time
     * @param resourceId     node's viewIdResourceName (may be null)
     * @param contentDesc    node's content-description (may be null)
     * @param hint           node's hintText (may be null)
     * @param className      node's class name (may be null)
     * @param isEditable     node.isEditable
     * @param boundsQuadrant precomputed screen quadrant
     * @param generationId   current RootGeneration value
     */
    fun resolveFocused(
        packageId: String,
        resourceId: String?,
        contentDesc: String?,
        hint: String?,
        className: String?,
        isEditable: Boolean,
        boundsQuadrant: BoundsQuadrant?,
        generationId: Long
    ): SemanticIdentity {
        val fp = FocusFingerprint(
            resourceId = resourceId,
            className = className,
            contentDesc = contentDesc,
            textHash = hint?.hashCode(),
            boundsQuadrant = boundsQuadrant,
            isEditable = isEditable
        )

        // Try each role classifier in priority order. First match wins.
        val identity: SemanticIdentity? = when {
            isSearchInput(resourceId, contentDesc, hint, className, isEditable, boundsQuadrant) ->
                SemanticIdentity.SearchInput(
                    packageId = packageId,
                    generationId = generationId,
                    fingerprint = fp
                )

            isChatList(resourceId, className, boundsQuadrant) ->
                SemanticIdentity.ChatList(
                    packageId = packageId,
                    generationId = generationId,
                    fingerprint = fp
                )

            else -> null
        }

        val resolved = identity ?: SemanticIdentity.Unknown(
            packageId = packageId,
            generationId = generationId,
            hint = buildString {
                append("editable=$isEditable")
                if (resourceId != null) append(" rid=$resourceId")
                if (className != null) append(" cls=${className.substringAfterLast('.')}")
                if (contentDesc != null) append(" cd='${contentDesc.take(30)}'")
            }
        )

        Log.i(TAG, "RESOLVED pkg=$packageId role=${resolved::class.simpleName} " +
                "rid=$resourceId cd='${contentDesc?.take(20)}' cls=${className?.substringAfterLast('.')}")

        return registry.put(resolved)
    }

    // ------------------------------------------------------------------
    // Role classifiers — generic patterns. Step 9 layers app-specific
    // overrides on top.
    // ------------------------------------------------------------------

    private fun isSearchInput(
        resourceId: String?,
        contentDesc: String?,
        hint: String?,
        className: String?,
        isEditable: Boolean,
        quadrant: BoundsQuadrant?
    ): Boolean {
        // Must be editable. Pure prerequisite.
        if (!isEditable) return false

        // Search-implying signals:
        val ridMatches = resourceId?.let { id ->
            id.contains("search", true) ||
                    id.endsWith("/search_input", true) ||
                    id.endsWith("/search_src_text", true) ||
                    id.endsWith("/my_search_bar", true) ||
                    id.endsWith("/query", true)
        } == true

        val descMatches = contentDesc?.let { d ->
            d.contains("search", true) ||
                    d.contains("ask meta", true)   // WhatsApp's "Ask Meta AI or Search"
        } == true

        val hintMatches = hint?.let { h ->
            h.contains("search", true) ||
                    h.contains("find", true)
        } == true

        val classMatches = className?.endsWith("EditText", true) == true ||
                className?.contains("TextField", true) == true

        // Top-band quadrant is typical for search bars. Not required —
        // some apps put search at the center.
        val topBand = quadrant == BoundsQuadrant.TOP_LEFT ||
                quadrant == BoundsQuadrant.TOP_CENTER ||
                quadrant == BoundsQuadrant.TOP_RIGHT

        // Decision: at least one strong textual signal, OR
        // (generic editable class + top quadrant + any weak signal).
        return when {
            ridMatches -> true
            descMatches -> true
            hintMatches -> true
            classMatches && topBand && (resourceId != null || contentDesc != null) -> true
            else -> false
        }
    }

    private fun isChatList(
        resourceId: String?,
        className: String?,
        quadrant: BoundsQuadrant?
    ): Boolean {
        val ridMatches = resourceId?.let { id ->
            id.contains("conversation", true) ||
                    id.contains("chat_list", true) ||
                    id.contains("thread_list", true)
        } == true

        val classMatches = className?.let { c ->
            c.endsWith("RecyclerView", true) || c.endsWith("ListView", true)
        } == true

        return ridMatches || (classMatches && quadrant != BoundsQuadrant.TOP_CENTER)
    }

    companion object {
        private const val TAG = "SemanticResolver"
    }
}