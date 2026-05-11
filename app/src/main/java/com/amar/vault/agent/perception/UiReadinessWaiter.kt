package com.amar.vault.agent.perception

import android.util.Log
import kotlinx.coroutines.delay

/**
 * Waits for an app's UI to be "ready for interaction" instead of using a
 * hardcoded delay.
 *
 * Why dynamic polling beats a fixed sleep:
 *   - Pixel 9: Settings paints in ~250ms. A 1500ms delay wastes 1.25s every run.
 *   - Low-end Redmi: Settings can take 2500ms. A 1500ms delay fires too early
 *     and we click on a half-loaded tree.
 *   The same code can't make both happy with a fixed value.
 *
 * Definition of "ready":
 *   The foreground snapshot matches the target package AND the tree contains
 *   more than [minElements] nodes. A splash screen typically has 1-3 nodes;
 *   a real UI has 10+. We default to >5 to clear loading screens without
 *   demanding a full content render.
 *
 * Returns:
 *   The settled [UiSnapshot] on success, or null on timeout.
 */
object UiReadinessWaiter {

    private const val TAG = "UiReadinessWaiter"
    private const val DEFAULT_POLL_INTERVAL_MS = 150L
    private const val DEFAULT_MIN_ELEMENTS = 5
    private const val DEFAULT_TIMEOUT_MS = 4_000L

    /**
     * Poll the accessibility tree until [targetPackage] is foreground and
     * the tree has at least [minElements] nodes.
     *
     * @param svc Bound PerceptionService.
     * @param targetPackage Package id we expect to see foreground.
     * @param timeoutMs Hard cap on waiting. Default 4s.
     * @param minElements Minimum tree size to consider "rendered". Default 5.
     * @param pollIntervalMs Time between snapshot attempts. Default 150ms.
     * @return The settled snapshot, or null on timeout.
     */
    suspend fun waitForUi(
        svc: PerceptionService,
        targetPackage: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        minElements: Int = DEFAULT_MIN_ELEMENTS,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS
    ): UiSnapshot? {
        val startTime = System.currentTimeMillis()
        var latest: UiSnapshot? = null

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            latest = svc.forceSnapshot()

            if (latest.packageId == targetPackage && latest.size > minElements) {
                Log.i(TAG, "UI settled in ${System.currentTimeMillis() - startTime}ms " +
                        "(pkg=${latest.packageId}, elements=${latest.size})")
                return latest
            }

            delay(pollIntervalMs)
        }

        Log.w(TAG, "Timeout waiting for $targetPackage " +
                "(last pkg=${latest?.packageId}, elements=${latest?.size})")
        return null
    }
}