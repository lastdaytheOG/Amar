package com.amar.vault.agent.runtime.environment

import com.amar.vault.agent.perception.UiElement
import com.amar.vault.agent.runtime.state.WorldState

/**
 * A SemanticEnvironment represents a distinct *operating mode* inside an
 * Android package. Many modern apps (superapps, embedded assistants,
 * multi-flow shopping apps) host several environments inside one APK that
 * share the same package id but are functionally separate UIs.
 *
 * Examples:
 *   - com.google.android.googlequicksearchbox hosts BOTH Google Search and
 *     Gemini, which look superficially similar (both have a search input)
 *     but are different operating environments. ACTION_SEARCH lands in
 *     Search; ACTION_ASSIST historically lands in Gemini.
 *   - Uber's package hosts ride-hailing AND food delivery flows.
 *   - Amazon's package hosts product search AND checkout flow.
 *
 * The runtime must validate which ENVIRONMENT it has reached, not just
 * which package — otherwise it injects text into the wrong surface.
 *
 * # Architecture
 *
 * Each environment defines:
 *   - [name] — for logging and error messages
 *   - [packageIds] — packages this environment can appear in
 *   - [requiredSignals] — signals that MUST be present (weighted)
 *   - [forbiddenSignals] — signals that MUST be absent (strong negative)
 *   - [confidenceThreshold] — minimum score to declare environment "reached"
 *
 * # Confidence scoring
 *
 * For each signal, [EnvironmentVerifier] checks presence against the
 * current WorldState + UiSnapshot. Present required signals add to score
 * weighted by their [EnvironmentSignal.weight]. Present forbidden signals
 * subtract from score. Final confidence is normalized to [0.0, 1.0].
 *
 * # Why this exists vs FrameworkAdapter
 *
 * FrameworkAdapter classifies a single focused node (is this an editable
 * SearchInput?). SemanticEnvironment classifies the WHOLE screen state
 * (am I in Gemini conversational mode or Google Search results mode?).
 * They operate at different abstraction levels.
 */
interface SemanticEnvironment {

    val name: String

    val packageIds: Set<String>

    val requiredSignals: List<EnvironmentSignal>

    val forbiddenSignals: List<EnvironmentSignal>

    /**
     * Minimum confidence (0.0–1.0) to declare this environment is reached.
     * Conservative environments (high-stakes injection targets like Gemini)
     * use higher thresholds (0.7+). Lenient environments use lower (0.4).
     */
    val confidenceThreshold: Double

    /**
     * Optional pre-injection recovery: if this environment is detected but
     * confidence is below threshold, the verifier can invoke a recovery
     * action specific to this environment (e.g. tap a "Switch to Gemini"
     * button, send a deep-link intent, scroll).
     *
     * Returns null if no automatic recovery is available.
     */
    fun recoveryStrategy(): EnvironmentRecovery? = null
}

/**
 * A single observable signal that contributes to environment confidence.
 *
 * Signals examine the current UI state and return either present (true)
 * or absent (false). They don't measure "how much" — confidence is built
 * by summing weighted booleans.
 */
sealed interface EnvironmentSignal {
    /** Higher = more diagnostic of this environment. Range: 0.0–1.0. */
    val weight: Double

    /** Human-readable description for logs. */
    val description: String

    /** Evaluate against the current UI state. */
    fun isPresent(snapshot: List<UiElement>, worldState: WorldState): Boolean

    data class HasResourceIdContaining(
        val substring: String,
        override val weight: Double = 0.5,
        override val description: String = "resource_id contains '$substring'"
    ) : EnvironmentSignal {
        override fun isPresent(snapshot: List<UiElement>, worldState: WorldState): Boolean =
            snapshot.any { it.resourceId?.contains(substring, ignoreCase = true) == true }
    }

    data class HasText(
        val text: String,
        override val weight: Double = 0.4,
        override val description: String = "visible text '$text'"
    ) : EnvironmentSignal {
        override fun isPresent(snapshot: List<UiElement>, worldState: WorldState): Boolean =
            snapshot.any { it.text?.contains(text, ignoreCase = true) == true }
    }

    data class HasContentDescContaining(
        val substring: String,
        override val weight: Double = 0.4,
        override val description: String = "content_desc contains '$substring'"
    ) : EnvironmentSignal {
        override fun isPresent(snapshot: List<UiElement>, worldState: WorldState): Boolean =
            snapshot.any { it.contentDesc?.contains(substring, ignoreCase = true) == true }
    }

    data class HasEditableField(
        override val weight: Double = 0.3,
        override val description: String = "at least one editable field"
    ) : EnvironmentSignal {
        override fun isPresent(snapshot: List<UiElement>, worldState: WorldState): Boolean =
            snapshot.any { it.editable }
    }
}

/**
 * Optional automatic recovery to attempt when an environment is detected
 * but confidence is insufficient.
 */
sealed interface EnvironmentRecovery {
    /** Send an Android Intent (e.g. deep-link to the right surface). */
    data class SendIntent(val action: String, val packageName: String) : EnvironmentRecovery

    /** Tap a UI affordance by text or resource_id. */
    data class TapAffordance(val target: String, val byText: Boolean = true) : EnvironmentRecovery
}