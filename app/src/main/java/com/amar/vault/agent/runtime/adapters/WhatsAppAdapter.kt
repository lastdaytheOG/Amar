package com.amar.vault.agent.runtime.adapters

import com.amar.vault.agent.runtime.injection.InjectionStrategy
import com.amar.vault.agent.runtime.state.BoundsQuadrant
import com.amar.vault.agent.runtime.state.FocusFingerprint
import com.amar.vault.agent.runtime.state.SemanticIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-app adapter for WhatsApp (com.whatsapp, com.whatsapp.w4b).
 *
 * Why this exists despite the generic engine already working:
 *   The generic SemanticResolver classified WhatsApp's search bar correctly
 *   in our testing because its resource_id (`com.whatsapp:id/search_input`)
 *   matches the generic "search" substring pattern. But WhatsApp also has
 *   several specific fields that benefit from explicit handling:
 *     - `com.whatsapp:id/my_search_bar` is the OUTER search container, not
 *       the inner EditText. Classifying it as SearchInput would have the
 *       engine inject into a non-editable wrapper.
 *     - `com.whatsapp:id/entry` is the message composer in chat threads —
 *       different intent than search.
 *
 * The adapter also documents WhatsApp's zero-width space prefix behavior
 * (already handled in ConfidenceEngine normalization) so future maintainers
 * see the connection.
 *
 * Strategies: defers to the generic cascade. ACTION_SET_TEXT works on
 * WhatsApp; no overrides needed.
 */
@Singleton
class WhatsAppAdapter @Inject constructor() : FrameworkAdapter {

    override val packageIds: Set<String> = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b"   // WhatsApp Business
    )

    override fun classifyFocusedNode(
        packageId: String,
        resourceId: String?,
        contentDesc: String?,
        hint: String?,
        className: String?,
        isEditable: Boolean,
        boundsQuadrant: BoundsQuadrant?,
        generationId: Long
    ): SemanticIdentity? {
        if (!isEditable) return null

        val fp = FocusFingerprint(
            resourceId = resourceId,
            className = className,
            contentDesc = contentDesc,
            textHash = hint?.hashCode(),
            boundsQuadrant = boundsQuadrant,
            isEditable = true
        )

        // search_input is the inner EditText. Definitive SearchInput.
        if (resourceId == "com.whatsapp:id/search_input" ||
            resourceId == "com.whatsapp.w4b:id/search_input"
        ) {
            return SemanticIdentity.SearchInput(
                packageId = packageId,
                generationId = generationId,
                fingerprint = fp
            )
        }

        // Search by content-desc — WhatsApp's "Ask Meta AI or Search" label.
        if (contentDesc?.contains("Ask Meta AI or Search", true) == true ||
            contentDesc?.equals("Search", true) == true
        ) {
            return SemanticIdentity.SearchInput(
                packageId = packageId,
                generationId = generationId,
                fingerprint = fp
            )
        }

        // entry is the message composer inside a chat. NOT search.
        // Return null so the generic resolver handles it as Unknown editable.
        if (resourceId == "com.whatsapp:id/entry" ||
            resourceId == "com.whatsapp.w4b:id/entry"
        ) {
            return null
        }

        // Defer to generic for everything else.
        return null
    }
}