package com.amar.vault.agent.perception

import android.util.Log
import kotlinx.coroutines.delay

object UiReadinessWaiter {

    private const val TAG = "UiReadinessWaiter"
    private const val DEFAULT_POLL_INTERVAL_MS = 150L
    private const val DEFAULT_MIN_ELEMENTS = 5
    private const val DEFAULT_TIMEOUT_MS = 4_000L

    // Packages known to need longer warmup (Chromium a11y bridge,
    // WebView init, Compose first-frame). Prefix match.
    private val SLOW_WARMUP_PACKAGES = mapOf(
        "com.brave.browser" to 8_000L,
        "com.android.chrome" to 8_000L,
        "com.microsoft.emmx" to 8_000L,
        "org.mozilla.firefox" to 8_000L,
        "com.duckduckgo" to 8_000L,
        "com.openai.chatgpt" to 8_000L,
        "com.anthropic.claude" to 8_000L,
    )

    private fun timeoutFor(targetPrefix: String, override: Long?): Long {
        if (override != null) return override

        SLOW_WARMUP_PACKAGES.forEach { (pkg, timeout) ->
            if (targetPrefix.startsWith(pkg)) return timeout
        }

        return DEFAULT_TIMEOUT_MS
    }

    suspend fun waitForUi(
        svc: PerceptionService,
        targetPackage: String,
        timeoutMs: Long? = null,
        minElements: Int = DEFAULT_MIN_ELEMENTS,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS
    ): UiSnapshot? {

        val targetPrefix = targetPackage.trimEnd('.')
        val effectiveTimeout = timeoutFor(targetPrefix, timeoutMs)

        val startTime = System.currentTimeMillis()
        var latest: UiSnapshot? = null
        var pollCount = 0

        Log.i(
            TAG,
            "waitForUi start: target=$targetPrefix timeout=${effectiveTimeout}ms"
        )

        while (System.currentTimeMillis() - startTime < effectiveTimeout) {

            latest = svc.forceSnapshot()
            pollCount++

            val snapPkg = latest.packageId ?: ""
            val elements = latest.size
            val clickableCount = latest.elements.count { it.clickable }
            val editableCount = latest.elements.count { it.editable }

            val packageMatches = snapPkg.startsWith(targetPrefix)
            val enoughElements = elements >= minElements

            Log.i(
                TAG,
                "poll #$pollCount: target=$targetPrefix saw=$snapPkg " +
                        "elements=$elements (click:$clickableCount edit:$editableCount) " +
                        "pkgMatch=$packageMatches"
            )

            if (packageMatches && enoughElements) {

                Log.i(
                    TAG,
                    "UI settled in ${System.currentTimeMillis() - startTime}ms " +
                            "(saw=$snapPkg elements=$elements " +
                            "click=$clickableCount edit=$editableCount)"
                )

                return latest
            }

            delay(pollIntervalMs)
        }

        Log.w(
            TAG,
            "Timeout after ${System.currentTimeMillis() - startTime}ms " +
                    "for $targetPrefix " +
                    "(last saw=${latest?.packageId} " +
                    "elements=${latest?.size} polls=$pollCount)"
        )

        return null
    }
}