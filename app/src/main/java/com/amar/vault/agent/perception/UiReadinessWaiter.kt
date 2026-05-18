package com.amar.vault.agent.perception

import android.util.Log
import kotlinx.coroutines.delay

/**
 * Waits for an app's UI to be ready for interaction.
 *
 * Match semantics — PREFIX match, not exact equality.
 *   Apps often launch through a chain of activities where the foreground
 *   package shifts. Settings launches as `com.android.settings`, then
 *   OEM/intelligence layers (`com.android.settings.intelligence`,
 *   `com.motorola.coresettingsext`) can take over briefly without us
 *   knowing. Prefix-matching keeps the wait useful through those flips.
 *
 * Definition of "ready":
 *   Foreground package prefix matches target AND the tree has at least
 *   [minElements] visible nodes. Splash screens have 1-3 nodes; a real
 *   UI has 10+.
 *
 * Returns the settled snapshot, or null on timeout.
 */
object UiReadinessWaiter {

    private const val TAG = "UiReadinessWaiter"
    private const val DEFAULT_POLL_INTERVAL_MS = 150L
    private const val DEFAULT_MIN_ELEMENTS = 5
    private const val DEFAULT_TIMEOUT_MS = 4_000L

    suspend fun waitForUi(
        svc: PerceptionService,
        targetPackage: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        minElements: Int = DEFAULT_MIN_ELEMENTS,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS
    ): UiSnapshot? {
        val startTime = System.currentTimeMillis()
        var latest: UiSnapshot? = null
        var pollCount = 0
        val targetPrefix = targetPackage.trimEnd('.')

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            latest = svc.forceSnapshot()
            pollCount++

            val snapPkg = latest.packageId ?: ""
            val elements = latest.size
            val clickableCount = latest.elements.count { it.clickable }
            val editableCount = latest.elements.count { it.editable }
            val packageMatches = snapPkg.startsWith(targetPrefix)
            val enoughElements = elements >= minElements

            Log.i(TAG, "poll #$pollCount: target=$targetPrefix saw=$snapPkg " +
                    "elements=$elements (click:$clickableCount edit:$editableCount) pkgMatch=$packageMatches")

            if (packageMatches && enoughElements) {
                Log.i(TAG, "UI settled in ${System.currentTimeMillis() - startTime}ms " +
                        "(saw=$snapPkg elements=$elements click=$clickableCount edit=$editableCount)")
                return latest
            }

            delay(pollIntervalMs)
        }

        Log.w(TAG, "Timeout after ${System.currentTimeMillis() - startTime}ms for $targetPrefix " +
                "(last saw=${latest?.packageId} elements=${latest?.size} polls=$pollCount)")
        return null
    }

    /**
     * Delta Polling mechanism.
     * Captures successive snapshots and compares their accessibility trees to detect motion.
     * If the UI is moving (e.g., bottom-sheet sliding up), it waits until the Delta == 0.
     * Returns the settled snapshot.
     */
    suspend fun waitForMotionToSettle(
        svc: PerceptionService,
        timeoutMs: Long = 3000L,
        pollIntervalMs: Long = 50L
    ): UiSnapshot {
        val startTime = System.currentTimeMillis()
        var snapA = svc.forceSnapshot()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            delay(pollIntervalMs)
            val snapB = svc.forceSnapshot()

            val delta = compareTrees(snapA, snapB)
            if (delta == 0) {
                Log.i(TAG, "Motion settled in ${System.currentTimeMillis() - startTime}ms (delta=0)")
                return snapB
            }

            snapA = snapB
        }

        Log.w(TAG, "Motion did not settle after ${System.currentTimeMillis() - startTime}ms, returning latest snapshot")
        return snapA
    }

    /**
     * Motion-aware stabilization layer before EVERY critical action.
     * Enforces quiet-frame thresholds to detect UI animation/motion settling,
     * toolbar morph completion, keyboard attach completion.
     */
    suspend fun waitForStableUi(
        svc: PerceptionService,
        timeoutMs: Long = 3000L,
        pollIntervalMs: Long = 50L,
        requiredQuietFrames: Int = 3
    ): UiSnapshot {
        val startTime = System.currentTimeMillis()
        var snapA = svc.forceSnapshot()
        var quietFrames = 0

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            delay(pollIntervalMs)
            val snapB = svc.forceSnapshot()

            val delta = compareTrees(snapA, snapB)
            if (delta == 0) {
                quietFrames++
                if (quietFrames >= requiredQuietFrames) {
                    Log.i(TAG, "UI stable after ${System.currentTimeMillis() - startTime}ms (quiet frames=$quietFrames)")
                    return snapB
                }
            } else {
                quietFrames = 0 // Reset on motion
            }

            snapA = snapB
        }

        Log.w(TAG, "UI did not stabilize after ${System.currentTimeMillis() - startTime}ms, returning latest snapshot")
        return snapA
    }

    /**
     * Run a quick bounds-comparison between two accessibility trees.
     * Returns the number of elements that have changed size/position, or size difference.
     */
    private fun compareTrees(a: UiSnapshot, b: UiSnapshot): Int {
        if (a.size != b.size) return kotlin.math.abs(a.size - b.size)
        var delta = 0
        for (i in a.elements.indices) {
            val elA = a.elements[i]
            val elB = b.elements[i]
            if (elA.bounds != elB.bounds) {
                delta++
            }
            if (elA.focused != elB.focused) {
                delta++
            }
        }
        return delta
    }
}