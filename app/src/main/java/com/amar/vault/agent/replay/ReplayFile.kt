package com.amar.vault.agent.replay

import kotlinx.serialization.Serializable

@Serializable
data class ReplayFile(
    val schemaVersion: Int = 1,
    val workflowId: String,
    val goal: String,
    val packageId: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val outcome: ReplayOutcome? = null,
    val deviceContext: DeviceContext,
    val workflowTags: List<String> = emptyList(),
    val frames: List<ReplayFrame>
)

@Serializable
data class ReplayOutcome(
    val kind: String,                  // "Succeeded" | "Failed" | "Started"
    val route: String? = null,
    val detail: String? = null,
    val durationMs: Long,
    val failureClass: String? = null   // null on success; FailureClass enum name on failure
)

@Serializable
data class DeviceContext(
    val manufacturer: String,
    val model: String,
    val androidSdk: Int,
    val screenDensity: Float,
    val windowSize: String             // "1080x2400"
)

/**
 * Canonical failure class taxonomy. When a workflow fails, the recorder
 * tags it with one of these so telemetry can cluster failures by *kind*
 * rather than per-app. Critical for the failure-class analysis in Step 6.
 *
 * Keep this list short and meaningful. Add new classes only when a new
 * generalizable failure pattern is observed across 3+ apps.
 */
object FailureClass {
    const val FAKE_EDITABLE = "FAKE_EDITABLE"                  // ghost/collapsed_text/open_search trap
    const val DELAYED_IME_ACTIVATION = "DELAYED_IME_ACTIVATION" // typing fired before IME bound
    const val MISSING_A11Y_SEMANTICS = "MISSING_A11Y_SEMANTICS" // snapshot too thin / Compose tree blind
    const val DYNAMIC_AFFORDANCE_MUTATION = "DYNAMIC_AFFORDANCE_MUTATION" // post-type Send vanished/morphed
    const val COMPOSE_TRUNCATION = "COMPOSE_TRUNCATION"        // DEPTH_LIMIT hit, missed deep nodes
    const val TRANSITION_RACE = "TRANSITION_RACE"              // verify ran during recompose
    const val WRONG_ENVIRONMENT = "WRONG_ENVIRONMENT"          // verifier rejected the surface
    const val ENV_RECOVERY_FAILED = "ENV_RECOVERY_FAILED"      // recovery strategy didn't restore env
    const val BACK_DESTROYED_STATE = "BACK_DESTROYED_STATE"    // step 2.5 BACK exited app
    const val NO_SEARCH_AFFORDANCE = "NO_SEARCH_AFFORDANCE"    // step 3 found nothing clickable
    const val INJECTION_REJECTED = "INJECTION_REJECTED"        // ACTION_SET_TEXT returned false
    const val SEND_FAILED = "SEND_FAILED"                      // typed but send button never fired
    const val APP_LAUNCH_FAILED = "APP_LAUNCH_FAILED"          // step 1 OpenApp didn't open
    const val UNKNOWN = "UNKNOWN"                              // catch-all for unclassified failures
}