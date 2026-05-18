package com.amar.vault.agent.perception

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits until the accessibility tree stops mutating for [quietFrames]
 * consecutive snapshots taken [intervalMs] apart.
 *
 * Stability signals checked per pair of consecutive snapshots:
 *   - element count delta == 0
 *   - focused node identity stable (same path or same resourceId)
 *   - top-3 element bounds stable (sum of bounds-corner deltas < 8px)
 *
 * Returns a [StabilityReport] capturing the final snapshot and a quality
 * score (0.0..1.0). 1.0 means perfectly stable across all quiet frames.
 *
 * Use cases:
 *   - After a click, before typing (Phase 4 input pipeline)
 *   - Before reading the focused node for SemanticLock (Phase 3)
 *   - Before declaring "search did not open" (Phase 5 recovery decision)
 *
 * This REPLACES the Phase 1 Delta-Zero animation lock with a more rigorous
 * version: count + focus + bounds, not just count.
 */
object UiStabilityWaiter {

    private const val TAG = "UiStabilityWaiter"
    private const val BOUNDS_DELTA_THRESHOLD = 8  // px

    suspend fun waitForStableUi(
        svc: PerceptionService,
        timeoutMs: Long = 3_000L,
        quietFrames: Int = 3,
        intervalMs: Long = 80L
    ): StabilityReport {
        var previous: UiSnapshot? = null
        var quietStreak = 0
        var totalFrames = 0
        var lastSnap: UiSnapshot? = null

        val finalSnap: UiSnapshot? = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val current = svc.forceSnapshot()
                lastSnap = current
                totalFrames++

                val prev = previous
                if (prev != null && isStable(prev, current)) {
                    quietStreak++
                    if (quietStreak >= quietFrames) {
                        return@withTimeoutOrNull current
                    }
                } else {
                    quietStreak = 0
                }

                previous = current
                delay(intervalMs)
            }
            @Suppress("UNREACHABLE_CODE") null
        }

        val achieved = finalSnap != null
        val score = if (achieved) 1.0 else (quietStreak.toDouble() / quietFrames).coerceIn(0.0, 1.0)
        Log.i(TAG, "UI_STABILITY_SCORE achieved=$achieved score=%.2f frames=$totalFrames quietStreak=$quietStreak"
            .format(score))

        return StabilityReport(
            achieved = achieved,
            score = score,
            framesSampled = totalFrames,
            quietStreak = quietStreak,
            finalSnapshot = finalSnap ?: lastSnap
        )
    }

    private fun isStable(a: UiSnapshot, b: UiSnapshot): Boolean {
        if (a.elements.size != b.elements.size) return false

        // Focused node identity (path or resourceId match).
        val focusedA = a.elements.firstOrNull { it.focused }
        val focusedB = b.elements.firstOrNull { it.focused }
        if ((focusedA == null) != (focusedB == null)) return false
        if (focusedA != null && focusedB != null) {
            val sameNode = focusedA.path == focusedB.path ||
                    (focusedA.resourceId != null && focusedA.resourceId == focusedB.resourceId)
            if (!sameNode) return false
        }

        // Top-3 element bounds drift.
        val topA = a.elements.take(3)
        val topB = b.elements.take(3)
        var drift = 0
        for (i in topA.indices) {
            if (i >= topB.size) break
            val ba = topA[i].bounds ?: continue
            val bb = topB[i].bounds ?: continue
            drift += kotlin.math.abs(ba.left - bb.left) +
                    kotlin.math.abs(ba.top - bb.top) +
                    kotlin.math.abs(ba.right - bb.right) +
                    kotlin.math.abs(ba.bottom - bb.bottom)
        }
        return drift < BOUNDS_DELTA_THRESHOLD
    }

    data class StabilityReport(
        val achieved: Boolean,
        val score: Double,
        val framesSampled: Int,
        val quietStreak: Int,
        val finalSnapshot: UiSnapshot?
    )
}