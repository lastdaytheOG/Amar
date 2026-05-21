package com.amar.vault.agent.runtime.environment

import android.util.Log
import com.amar.vault.agent.perception.PerceptionService
import com.amar.vault.agent.perception.SnapshotCache
import com.amar.vault.agent.runtime.state.WorldStateStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fuses multiple signals (accessibility tree, WorldState focused identity,
 * snapshot cache) to compute confidence that a target [SemanticEnvironment]
 * has been reached.
 *
 * Called by UiSearchExecutor BEFORE step 3 (clicking) and BEFORE step 8
 * (injection). If the wrong environment is detected, the executor must
 * not inject text — it should recover or abort.
 *
 * # Algorithm
 *   For each candidate environment matching the foreground package:
 *     score = sum(weight of present required signals)
 *           - sum(weight of present forbidden signals)
 *     normalized = max(0, min(1, score / sum(required weights)))
 *
 *   Verifier returns:
 *     - the highest-scoring environment among all candidates
 *     - all per-environment confidences for diagnostics
 *     - whether the requested target environment crossed its threshold
 */
@Singleton
class EnvironmentVerifier @Inject constructor(
    private val snapshotCache: SnapshotCache,
    private val worldStateStore: WorldStateStore,
    private val environments: Set<@JvmSuppressWildcards SemanticEnvironment>
) {

    /**
     * Lightweight state enum capturing whether the verifier has high
     * confidence in its decision, vs. is observing an unstable / loading
     * UI vs. is genuinely seeing the wrong environment.
     *
     * Step 1 of the roadmap only introduces the *states* — recovery
     * decisions branch on them. Hysteresis, rolling confidence memory,
     * and async monitoring loops come in later steps once we have real
     * telemetry to justify them.
     */
    enum class EnvironmentState {
        /** Target environment confidence ≥ threshold. Safe to execute. */
        STABLE,
        /** Some signals present but below threshold. UI may be loading
         *  or animating. Caller should wait and retry, not fail. */
        TRANSITIONING,
        /** Multiple environments scoring close together. Genuinely
         *  unclear which one is active. Caller should escalate. */
        AMBIGUOUS,
        /** A different environment definitively won. Caller should
         *  invoke environment-specific recovery before retrying. */
        WRONG_ENVIRONMENT,
        /** No environment signals present at all. UI may be blank,
         *  permission dialog, etc. */
        UNKNOWN
    }

    data class VerificationResult(
        val targetEnvironment: SemanticEnvironment,
        val targetConfidence: Double,
        val targetReached: Boolean,
        val winningEnvironment: SemanticEnvironment?,
        val allConfidences: Map<String, Double>,
        val state: EnvironmentState
    )

    fun verify(targetEnvironment: SemanticEnvironment): VerificationResult {
        val realPackage = targetEnvironment.packageIds.firstOrNull()
        val snapshot = snapshotCache.currentAnyAge()?.elements ?: emptyList()
        val worldState = worldStateStore.current()

        val applicable = environments.filter { env ->
            env.packageIds.any { it == realPackage }
        }

        val confidences = mutableMapOf<String, Double>()
        for (env in applicable) {
            val totalWeight = env.requiredSignals.sumOf { it.weight }
            val presentRequired = env.requiredSignals
                .filter { it.isPresent(snapshot, worldState) }
                .sumOf { it.weight }
            val presentForbidden = env.forbiddenSignals
                .filter { it.isPresent(snapshot, worldState) }
                .sumOf { it.weight }

            val raw = if (totalWeight > 0) {
                (presentRequired - presentForbidden) / totalWeight
            } else 0.0
            val normalized = raw.coerceIn(0.0, 1.0)
            confidences[env.name] = normalized

            Log.i(TAG, "ENV_SCORE name=${env.name} " +
                    "present_required=$presentRequired/$totalWeight " +
                    "forbidden=$presentForbidden " +
                    "confidence=${"%.2f".format(normalized)}")
        }

        val targetConfidence = confidences[targetEnvironment.name] ?: 0.0
        val targetReached = targetConfidence >= targetEnvironment.confidenceThreshold
        val winningEnvironment = confidences.maxByOrNull { it.value }?.let { entry ->
            applicable.firstOrNull { it.name == entry.key }
        }

        Log.i(TAG, "VERIFY target='${targetEnvironment.name}' " +
                "conf=${"%.2f".format(targetConfidence)} " +
                "threshold=${targetEnvironment.confidenceThreshold} " +
                "reached=$targetReached " +
                "winner='${winningEnvironment?.name}'")

        // Step 1: classify into a coarse state. No hysteresis or rolling
        // memory yet — just snapshot-derived classification.
        val state = classifyState(
            targetEnvironment = targetEnvironment,
            targetConfidence = targetConfidence,
            winningEnvironment = winningEnvironment,
            allConfidences = confidences
        )
        Log.i(TAG, "STATE target='${targetEnvironment.name}' state=$state")

        return VerificationResult(
            targetEnvironment = targetEnvironment,
            targetConfidence = targetConfidence,
            targetReached = targetReached,
            winningEnvironment = winningEnvironment,
            allConfidences = confidences,
            state = state
        )
    }

    /**
     * Coarse classifier mapping raw confidence values to a runtime state.
     *
     * Heuristics:
     *   - STABLE              → target conf ≥ threshold
     *   - WRONG_ENVIRONMENT   → another env beat the target with high margin
     *   - AMBIGUOUS           → top two envs are within 0.15 of each other
     *   - TRANSITIONING       → some signals fire (>0) but below threshold
     *   - UNKNOWN             → no env scored above zero
     */
    private fun classifyState(
        targetEnvironment: SemanticEnvironment,
        targetConfidence: Double,
        winningEnvironment: SemanticEnvironment?,
        allConfidences: Map<String, Double>
    ): EnvironmentState {
        if (targetConfidence >= targetEnvironment.confidenceThreshold) {
            return EnvironmentState.STABLE
        }
        val maxConf = allConfidences.values.maxOrNull() ?: 0.0
        if (maxConf <= 0.05) {
            return EnvironmentState.UNKNOWN
        }
        val sorted = allConfidences.values.sortedDescending()
        val top = sorted.getOrNull(0) ?: 0.0
        val second = sorted.getOrNull(1) ?: 0.0
        if (top - second < 0.15 && top > 0.2) {
            return EnvironmentState.AMBIGUOUS
        }
        if (winningEnvironment != null &&
            winningEnvironment.name != targetEnvironment.name &&
            (allConfidences[winningEnvironment.name] ?: 0.0) >= 0.3
        ) {
            return EnvironmentState.WRONG_ENVIRONMENT
        }
        return EnvironmentState.TRANSITIONING
    }

    fun findByName(name: String): SemanticEnvironment? =
        environments.firstOrNull { it.name == name }

    companion object {
        private const val TAG = "EnvVerifier"
    }
}