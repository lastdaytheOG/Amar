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

    data class VerificationResult(
        val targetEnvironment: SemanticEnvironment,
        val targetConfidence: Double,
        val targetReached: Boolean,
        val winningEnvironment: SemanticEnvironment?,
        val allConfidences: Map<String, Double>
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

        return VerificationResult(
            targetEnvironment = targetEnvironment,
            targetConfidence = targetConfidence,
            targetReached = targetReached,
            winningEnvironment = winningEnvironment,
            allConfidences = confidences
        )
    }

    fun findByName(name: String): SemanticEnvironment? =
        environments.firstOrNull { it.name == name }

    companion object {
        private const val TAG = "EnvVerifier"
    }
}