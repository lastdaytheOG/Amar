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
        // Runtime foreground package when Gemini is open. The .bard
        // launch identity redirects here.
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.bard"
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
        EnvironmentSignal.HasText("Recent searches", weight = 0.3),
        // AI Mode (AIM) inside Google Search is a *different* surface than
        // native Gemini. If we see AIM resource ids, we landed on the wrong
        // Gemini-like surface and must NOT inject there.
        EnvironmentSignal.HasResourceIdContaining("googleapp_aim", weight = 0.5),
        EnvironmentSignal.HasResourceIdContaining("searchbox_aim", weight = 0.5),
        EnvironmentSignal.HasText("Ask anything", weight = 0.4)
    )

    override val confidenceThreshold: Double = 0.5

    override fun recoveryStrategy(): EnvironmentRecovery =
    // Gemini's MainActivity is android:exported="false", so we cannot
    // launch it directly from a third-party app. Instead, when we land
    // in Google Search (the default ACTION_ASSIST surface on this
    // device), the search screen exposes an "AI Mode" chip with
    // resource-id googleapp_sbn_aim_chip that switches into Gemini
        // conversational mode. Tap that.
        EnvironmentRecovery.TapAffordance(
            target = "googleapp_sbn_aim_chip",
            byText = false  // resource-id, not text
        )
}