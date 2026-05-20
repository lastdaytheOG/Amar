package com.amar.vault.agent.runtime.injection

import android.util.Log
import com.amar.vault.agent.runtime.metrics.InjectionMetrics
import com.amar.vault.agent.runtime.state.SemanticIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reorders a strategy cascade based on observed per-strategy success rates
 * from [InjectionMetrics].
 *
 * # Policy
 *   - Need at least MIN_SAMPLES_PER_STRATEGY observations to consider
 *     reordering; otherwise keep adapter/default order.
 *   - Each strategy gets a score: success_rate * 1.0 + (-avg_duration/1000.0).
 *     Higher = better. Successful AND fast wins.
 *   - Strategies with no samples sit at default position; strategies with
 *     samples either rise (good score) or sink (bad score) around them.
 *   - Bound the reordering: never move a strategy more than MAX_RANK_SHIFT
 *     positions from its original index. Prevents catastrophic flips on
 *     thin data.
 *
 * # Why we don't just "use the winner"
 * The winner today might fail tomorrow (app update, OEM patch, new framework
 * version). Keeping the full cascade with reordered priority means we still
 * try other strategies if the leader fails — adaptive, not greedy.
 *
 * # Diagnostic
 * Logs the reorder decision with before/after positions when reorder occurs.
 * Quiet when no reorder applied (insufficient data).
 */
@Singleton
class AdaptiveCascadePolicy @Inject constructor(
    private val metrics: InjectionMetrics
) {

    /**
     * Reorder [original] strategies for the given identity. Returns either
     * the reordered list or [original] itself if not enough data exists.
     */
    fun reorder(
        identity: SemanticIdentity,
        original: List<InjectionStrategy>
    ): List<InjectionStrategy> {
        if (original.size <= 1) return original
        val identityKey = identity::class.simpleName ?: return original

        val snapshot = metrics.snapshot()
        val statsByStrategy = snapshot.perStrategy
            .filter { it.key.startsWith("$identityKey:") }
            .associateBy { it.key.substringAfter(":") }

        // Don't reorder unless every strategy with stats has at least MIN_SAMPLES.
        val anyEligible = statsByStrategy.values.any { it.attempts >= MIN_SAMPLES_PER_STRATEGY }
        if (!anyEligible) {
            return original
        }

        // Score each strategy. Unscored strategies inherit a neutral score
        // at their original position (no info → trust the default order).
        val scored = original.withIndex().map { (idx, strat) ->
            val stat = statsByStrategy[strat.name]
            val score: Double = if (stat != null && stat.attempts >= MIN_SAMPLES_PER_STRATEGY) {
                stat.successRate - (stat.avgDurationMs.toDouble() / 1000.0) * DURATION_PENALTY_WEIGHT
            } else {
                NEUTRAL_SCORE  // unscored → middle of the pack, position-anchored
            }
            Scored(strat, idx, score)
        }

        // Sort by score desc, then by original index asc (stable for ties).
        val sortedByScore = scored.sortedWith(
            compareByDescending<Scored> { it.score }.thenBy { it.originalIndex }
        )

        // Apply rank-shift bound: a strategy can't move more than
        // MAX_RANK_SHIFT positions from its original index. This guards
        // against catastrophic reorder from low-confidence data.
        val bounded = applyRankShiftBound(scored, sortedByScore)

        val reordered = bounded.map { it.strategy }
        val changed = reordered.zip(original).any { (a, b) -> a.name != b.name }

        if (changed) {
            val before = original.joinToString(",") { it.name }
            val after = reordered.joinToString(",") { it.name }
            Log.i(TAG, "REORDER identity=$identityKey before=[$before] after=[$after]")
            bounded.forEach { s ->
                val stat = statsByStrategy[s.strategy.name]
                Log.d(TAG, "  ${s.strategy.name} origIdx=${s.originalIndex} " +
                        "score=%.3f attempts=${stat?.attempts ?: 0} " +
                        "succ=${stat?.successes ?: 0} avgMs=${stat?.avgDurationMs ?: 0}"
                            .format(s.score))
            }
        }
        return reordered
    }

    private fun applyRankShiftBound(
        scored: List<Scored>,
        sortedByScore: List<Scored>
    ): List<Scored> {
        // Map original-index → desired-new-index from the sort.
        val desiredIndexByOriginal = sortedByScore.withIndex()
            .associate { (newIdx, s) -> s.originalIndex to newIdx }

        // Clamp each strategy's new index to within MAX_RANK_SHIFT of its
        // original. Then fix collisions by sliding adjacent entries.
        val clamped = scored.map { s ->
            val desired = desiredIndexByOriginal[s.originalIndex] ?: s.originalIndex
            val clampedIdx = desired.coerceIn(
                s.originalIndex - MAX_RANK_SHIFT,
                s.originalIndex + MAX_RANK_SHIFT
            )
            s to clampedIdx
        }

        // Stable sort by clamped index, fall back to original index on ties.
        return clamped
            .sortedWith(compareBy({ it.second }, { it.first.originalIndex }))
            .map { it.first }
    }

    private data class Scored(
        val strategy: InjectionStrategy,
        val originalIndex: Int,
        val score: Double
    )

    companion object {
        private const val TAG = "AdaptiveCascade"

        /** Minimum attempts before a strategy's score influences ordering. */
        private const val MIN_SAMPLES_PER_STRATEGY = 5L

        /** Score for unscored strategies (between succ=1.0 / dur=0 and total fail). */
        private const val NEUTRAL_SCORE = 0.5

        /** How many positions a strategy can shift from its original index. */
        private const val MAX_RANK_SHIFT = 2

        /** How heavily we penalize slow strategies. 0.0 = ignore duration. */
        private const val DURATION_PENALTY_WEIGHT = 0.1
    }
}