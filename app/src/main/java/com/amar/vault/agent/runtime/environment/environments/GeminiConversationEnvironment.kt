package com.amar.vault.agent.runtime.environment.environments

import com.amar.vault.agent.runtime.environment.EnvironmentRecovery
import com.amar.vault.agent.runtime.environment.EnvironmentSignal
import com.amar.vault.agent.runtime.environment.SemanticEnvironment
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Semantic environment for Google Gemini conversational mode.
 *
 * Distinguishing signals from Google Search:
 *   - "Ask Gemini" hint text in prompt input
 *   - assistant_robin_* resource ids (Gemini UI's internal namespace)
 *   - "Flash"/"Pro" model selector visible
 *   - "Open tools" / "Open Gemini Live" content_desc
 *
 * Forbidden signals (these indicate we landed in Google Search instead):
 *   - search_suggestion / search_autocomplete resource ids
 *   - "trending" or "recent searches" text
 *   - GoogleSearch / SearchActivity class names
 */
@Singleton
class GeminiConversationEnvironment @Inject constructor() : SemanticEnvironment {

    override val name: String = "GeminiConversation"

    override val packageIds: Set<String> = setOf(
        "com.google.android.googlequicksearchbox"
    )

    override val requiredSignals: List<EnvironmentSignal> = listOf(
        EnvironmentSignal.HasResourceIdContaining("assistant_robin", weight = 0.4),
        EnvironmentSignal.HasText("Ask Gemini", weight = 0.5),
        EnvironmentSignal.HasContentDescContaining("Open Gemini Live", weight = 0.3),
        EnvironmentSignal.HasContentDescContaining("Open tools", weight = 0.2),
        EnvironmentSignal.HasText("Gemini", weight = 0.2)
    )

    override val forbiddenSignals: List<EnvironmentSignal> = listOf(
        EnvironmentSignal.HasResourceIdContaining("search_suggestion", weight = 0.4),
        EnvironmentSignal.HasResourceIdContaining("search_autocomplete", weight = 0.4),
        EnvironmentSignal.HasText("Trending searches", weight = 0.3),
        EnvironmentSignal.HasText("Recent searches", weight = 0.3)
    )

    override val confidenceThreshold: Double = 0.5

    override fun recoveryStrategy(): EnvironmentRecovery =
        EnvironmentRecovery.SendIntent(
            action = "android.intent.action.ASSIST",
            packageName = "com.google.android.googlequicksearchbox"
        )
}