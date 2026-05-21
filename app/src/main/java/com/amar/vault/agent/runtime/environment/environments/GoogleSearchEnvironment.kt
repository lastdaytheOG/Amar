package com.amar.vault.agent.runtime.environment.environments

import com.amar.vault.agent.runtime.environment.EnvironmentSignal
import com.amar.vault.agent.runtime.environment.SemanticEnvironment
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Semantic environment for classic Google Search (web-search bar with
 * autocomplete and trending-search chips).
 *
 * Mirrors GeminiConversationEnvironment but with INVERTED signals. Used so
 * the verifier can distinguish "search opened Google Search by accident"
 * from "search correctly opened Gemini".
 */
@Singleton
class GoogleSearchEnvironment @Inject constructor() : SemanticEnvironment {

    override val name: String = "GoogleSearch"

    override val packageIds: Set<String> = setOf(
        "com.google.android.googlequicksearchbox"
    )

    override val requiredSignals: List<EnvironmentSignal> = listOf(
        EnvironmentSignal.HasResourceIdContaining("search_suggestion", weight = 0.5),
        EnvironmentSignal.HasResourceIdContaining("search_autocomplete", weight = 0.5),
        EnvironmentSignal.HasText("Trending searches", weight = 0.3),
        EnvironmentSignal.HasText("Recent searches", weight = 0.3),
        EnvironmentSignal.HasResourceIdContaining("googleapp", weight = 0.2)
    )

    override val forbiddenSignals: List<EnvironmentSignal> = listOf(
        EnvironmentSignal.HasResourceIdContaining("assistant_robin", weight = 0.5),
        EnvironmentSignal.HasText("Ask Gemini", weight = 0.5),
        EnvironmentSignal.HasContentDescContaining("Gemini Live", weight = 0.3)
    )

    override val confidenceThreshold: Double = 0.4
}