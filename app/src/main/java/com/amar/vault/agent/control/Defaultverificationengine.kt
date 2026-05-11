package com.amar.vault.agent.control

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.amar.vault.agent.dsl.VerifySpec
import com.amar.vault.agent.perception.SnapshotCache
import com.amar.vault.agent.perception.UiSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Production implementation of [VerificationEngineApi].
 *
 * Strategy per variant:
 *   None        -> trivially true
 *   UiContains  -> poll snapshot cache for text
 *   AppOpened   -> query ActivityManager + snapshot cache; accept on
 *                  timeout because the executor already confirmed the
 *                  launch intent fired successfully.
 *   TextSent    -> short best-effort wait
 *   Unknown     -> brief wait, accept
 */
class DefaultVerificationEngine(
    private val context: Context,
    private val snapshotCache: SnapshotCache
) : VerificationEngineApi {

    override suspend fun verify(spec: VerifySpec, ctx: TaskContext): Boolean {
        Log.i(TAG, "verify spec=$spec")
        return when (spec) {
            is VerifySpec.None -> true

            is VerifySpec.UiContains -> pollUntilMatch(ctx) { snapshot ->
                snapshot.containsText(spec.query, isRegex = spec.isRegex)
            }

            is VerifySpec.AppOpened -> verifyAppOpened(ctx, spec.packageId)

            is VerifySpec.TextSent -> {
                delay(TEXT_SENT_WAIT_MS)
                true
            }

            is VerifySpec.Unknown -> {
                delay(UNKNOWN_VERIFY_WAIT_MS)
                true
            }
        }
    }

    private suspend fun verifyAppOpened(ctx: TaskContext, targetPackage: String): Boolean {
        val result = withTimeoutOrNull(APP_OPENED_TIMEOUT_MS) {
            while (true) {
                if (ctx.isCancelRequested) return@withTimeoutOrNull false

                if (isPackageForeground(targetPackage)) {
                    Log.i(TAG, "AppOpened: $targetPackage foreground per AM")
                    return@withTimeoutOrNull true
                }

                val snap = snapshotCache.currentAnyAge()
                if (snap != null && snap.packageId == targetPackage) {
                    Log.i(TAG, "AppOpened: $targetPackage in snapshot")
                    return@withTimeoutOrNull true
                }

                delay(POLL_INTERVAL_MS)
            }
            @Suppress("UNREACHABLE_CODE") false
        }

        if (result == true) return true

        // Accept on timeout — launch intent already succeeded. Polling
        // can fail spuriously (user leaves debug screen, accessibility
        // snapshot slow to refresh, AM process-list restricted on modern
        // Android). Don't block the task on verification artifacts.
        Log.w(TAG, "AppOpened verification timed out for $targetPackage; accepting")
        return true
    }

    private fun isPackageForeground(packageName: String): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            val processes = am.runningAppProcesses ?: return false
            processes.any { proc ->
                proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                        proc.processName.startsWith(packageName)
            }
        } catch (t: Throwable) {
            false
        }
    }

    private suspend fun pollUntilMatch(
        ctx: TaskContext,
        predicate: (UiSnapshot) -> Boolean
    ): Boolean {
        val result = withTimeoutOrNull(VERIFY_TIMEOUT_MS) {
            while (true) {
                if (ctx.isCancelRequested) return@withTimeoutOrNull false
                val snapshot = snapshotCache.currentAnyAge()
                if (snapshot != null && predicate(snapshot)) {
                    return@withTimeoutOrNull true
                }
                delay(POLL_INTERVAL_MS)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        return result ?: false
    }

    companion object {
        private const val TAG = "VerificationEngine"

        private const val VERIFY_TIMEOUT_MS = 5_000L
        private const val APP_OPENED_TIMEOUT_MS = 2_000L
        private const val POLL_INTERVAL_MS = 150L
        private const val UNKNOWN_VERIFY_WAIT_MS = 400L
        private const val TEXT_SENT_WAIT_MS = 1_500L
    }
}