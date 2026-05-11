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
            val packageMatches = snapPkg.startsWith(targetPrefix)
            val enoughElements = elements >= minElements

            Log.i(TAG, "poll #$pollCount: target=$targetPrefix saw=$snapPkg " +
                    "elements=$elements pkgMatch=$packageMatches")

            if (packageMatches && enoughElements) {
                Log.i(TAG, "UI settled in ${System.currentTimeMillis() - startTime}ms " +
                        "(saw=$snapPkg elements=$elements)")
                return latest
            }

            delay(pollIntervalMs)
        }

        Log.w(TAG, "Timeout after ${System.currentTimeMillis() - startTime}ms for $targetPrefix " +
                "(last saw=${latest?.packageId} elements=${latest?.size} polls=$pollCount)")
        return null
    }
}