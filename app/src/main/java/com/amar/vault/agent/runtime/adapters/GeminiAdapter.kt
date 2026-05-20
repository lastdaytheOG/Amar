package com.amar.vault.agent.runtime.adapters

import com.amar.vault.agent.runtime.state.BoundsQuadrant
import com.amar.vault.agent.runtime.state.FocusFingerprint
import com.amar.vault.agent.runtime.state.SemanticIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapter for Google Gemini.
 *
 * Gemini's UI actually lives inside the Google Quicksearchbox app
 * (com.google.android.googlequicksearchbox), not a standalone "bard"
 * package — Google embedded the Gemini assistant into the system-wide
 * Quicksearchbox app starting late 2024.
 *
 * The prompt input has resource-id ending in
 * "assistant_robin_input_collapsed_text_half_sheet" and visible text
 * "Ask Gemini" (which sits in the text/hint slot, not contentDesc).
 */
@Singleton
class GeminiAdapter @Inject constructor() : FrameworkAdapter {

    override val packageIds: Set<String> = setOf(
        "com.google.android.googlequicksearchbox"
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

        // The collapsed text field is a fake input that must be clicked first.
        // If we classify it as SearchInput, the injection engine will try to type into it directly and fail.
        if (resourceId?.contains("assistant_robin_input_collapsed_text", true) == true) {
            return null
        }

        // Resource-id match — primary signal.
        if (resourceId?.contains("assistant_robin_chat_input", true) == true ||
            resourceId?.contains("gemini_chat_input", true) == true
        ) {
            return SemanticIdentity.SearchInput(
                packageId = packageId,
                generationId = generationId,
                fingerprint = fp
            )
        }

        // Hint/text fallback.
        if (hint?.contains("Ask Gemini", true) == true ||
            contentDesc?.contains("Ask Gemini", true) == true
        ) {
            return SemanticIdentity.SearchInput(
                packageId = packageId,
                generationId = generationId,
                fingerprint = fp
            )
        }

        return null
    }
}