package com.amar.vault.agent.control

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.amar.vault.agent.dsl.VerifySpec
import com.amar.vault.agent.perception.PerceptionDiagnostics
import com.amar.vault.agent.perception.PerceptionService
import com.amar.vault.agent.perception.SearchContextVerifier
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

            is VerifySpec.SearchOpened -> verifySearchOpened(ctx)

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

    /**
     * Semantic search context verification.
     *
     * Replaces blind structural-change verification. Polls the snapshot cache
     * for POSITIVE evidence of search context (editable nodes, search hints)
     * and rejects screens with disqualifying semantics (scan/qr/camera/payment/upi).
     *
     * This is the critical fix for the WhatsApp false positive where QR scanner
     * opening was incorrectly treated as "search opened" due to large structural change.
     */
    private suspend fun verifySearchOpened(ctx: TaskContext): Boolean {
        val result = withTimeoutOrNull(SEARCH_OPENED_TIMEOUT_MS) {
            while (true) {
                if (ctx.isCancelRequested) return@withTimeoutOrNull false

                val snapshot = snapshotCache.currentAnyAge()
                if (snapshot != null) {
                    val verification = SearchContextVerifier.verifySearchContext(snapshot)

                    if (verification.isSearchContext) {
                        Log.i(TAG, "SearchOpened verification PASSED: ${verification.toLogString()}")
                        return@withTimeoutOrNull true
                    }

                    // If we see disqualifying semantics, fail immediately — don't wait.
                    // The screen is definitively NOT a search context.
                    if (verification.negativeSignals.isNotEmpty()) {
                        Log.e(TAG, "SearchOpened verification FAILED (disqualifiers found): " +
                                "${verification.toLogString()}")

                        // Run perception diagnostic for debugging
                        val service = PerceptionService.get()
                        if (service != null && snapshot.elements.size < 20) {
                            Log.e(TAG, "Running perception diagnostic due to undersized snapshot")
                            PerceptionDiagnostics.runDiagnostic(service)
                        }

                        return@withTimeoutOrNull false
                    }

                    // No positive and no negative — keep polling (UI may still be transitioning)
                    Log.d(TAG, "SearchOpened: no evidence yet (elements=${snapshot.elements.size}), polling...")
                }

                delay(POLL_INTERVAL_MS)
            }
            @Suppress("UNREACHABLE_CODE") false
        }

        if (result == null || !result) {
            Log.w(TAG, "SearchOpened verification timed out or failed; rejecting")
            return false
        }

        return true
    }

    companion object {
        private const val TAG = "VerificationEngine"

        private const val VERIFY_TIMEOUT_MS = 5_000L
        private const val APP_OPENED_TIMEOUT_MS = 2_000L
        private const val POLL_INTERVAL_MS = 150L
        private const val UNKNOWN_VERIFY_WAIT_MS = 400L
        private const val TEXT_SENT_WAIT_MS = 1_500L
        private const val SEARCH_OPENED_TIMEOUT_MS = 4_000L
    }
}