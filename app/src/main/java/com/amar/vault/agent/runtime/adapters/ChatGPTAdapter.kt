package com.amar.vault.agent.runtime.adapters

import com.amar.vault.agent.runtime.state.BoundsQuadrant
import com.amar.vault.agent.runtime.state.FocusFingerprint
import com.amar.vault.agent.runtime.state.SemanticIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-app adapter for OpenAI ChatGPT (com.openai.chatgpt).
 *
 * ChatGPT's Android app is React Native, so accessibility nodes carry
 * generic className "android.view.ViewGroup" rather than EditText. The
 * generic SemanticResolver's editable-class check would miss the prompt
 * input.
 *
 * The prompt input has resource_id "prompt-textarea" (web-derived naming
 * convention from RN bindings). Classify it directly as SearchInput so
 * the engine treats it like any other search field — ChatGPT search and
 * ChatGPT prompt-entry are the same workflow from the user's perspective.
 *
 * No strategy overrides — ACTION_SET_TEXT works on RN TextInput once focus
 * is established.
 */
@Singleton
class ChatGPTAdapter @Inject constructor() : FrameworkAdapter {

    override val packageIds: Set<String> = setOf(
        "com.openai.chatgpt"
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

        // Direct match: the React Native binding ID for the prompt input.
        if (resourceId?.contains("prompt-textarea", true) == true ||
            resourceId?.contains("composer", true) == true ||
            resourceId?.contains("message_input", true) == true
        ) {
            return SemanticIdentity.SearchInput(
                packageId = packageId,
                generationId = generationId,
                fingerprint = fp
            )
        }

        // Content-desc fallback for the prompt input.
        if (contentDesc?.contains("Message ChatGPT", true) == true ||
            contentDesc?.contains("Ask anything", true) == true ||
            hint?.contains("Ask anything", true) == true ||
            hint?.contains("Message ChatGPT", true) == true
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