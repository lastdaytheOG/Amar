package com.amar.vault.agent.runtime.injection

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.amar.vault.agent.perception.PerceptionService
import com.amar.vault.agent.perception.RootGeneration
import com.amar.vault.agent.runtime.events.AccessibilityEventBus
import com.amar.vault.agent.runtime.events.AgentEvent
import com.amar.vault.agent.runtime.ime.ImeCoordinator
import com.amar.vault.agent.runtime.scheduler.ExecutionLane
import com.amar.vault.agent.runtime.scheduler.ExecutionScheduler
import com.amar.vault.agent.runtime.state.SemanticIdentity
import com.amar.vault.agent.runtime.state.WorldStateStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The elite injection engine. Orchestrates the strategy cascade with
 * mutation verification, ownership coordination, and clipboard hygiene.
 *
 * High-level flow for inject(text, identity):
 *   1. Await IME readiness (via ImeCoordinator.awaitReady).
 *   2. Snapshot clipboard contents (for restoration later).
 *   3. Acquire INJECTION lane ownership (serializes with other injection).
 *   4. For each strategy in cascade order:
 *      a. Acquire a FRESH focused-editable node via findFocus.
 *         Stale nodes are useless; re-acquire on every attempt.
 *      b. Verify the fresh node matches the target SemanticIdentity.
 *         If the user navigated away mid-attempt, abort.
 *      c. Call strategy.inject(node, text, identity).
 *      d. If mechanical success: run ConfidenceEngine.verify() for up to
 *         1500ms. If confidence >= threshold, declare verified success.
 *      e. Recycle the node before moving on.
 *   5. Restore clipboard in finally block (regardless of outcome).
 *
 * Returns [InjectionResult] with full per-strategy attempt history.
 *
 * NOT YET WIRED:
 *   The engine is callable from any Hilt-injected class today. Step 8 Part 2's
 *   executor patch adds the entry point from UiSearchExecutor. Until that
 *   patch is applied, nothing calls inject() and the legacy path is intact.
 */
@Singleton
class InjectionEngine @Inject constructor(
    private val bus: AccessibilityEventBus,
    private val store: WorldStateStore,
    private val imeCoordinator: ImeCoordinator,
    private val confidence: ConfidenceEngine,
    private val cascade: StrategyCascade,
    private val scheduler: ExecutionScheduler
) {

    /**
     * Inject [text] into the currently-focused editable, assumed to match
     * [expectedIdentity]. Suspends until either success, all strategies
     * exhausted, or timeout.
     *
     * Caller responsibilities:
     *   - Ensure the target affordance (e.g. search bar) is already opened
     *     such that an editable is focused. The engine does NOT click search
     *     bars or open dialogs — it ONLY injects into already-focused fields.
     *   - Provide the expected SemanticIdentity. The engine cross-checks the
     *     focused node's identity against this before each strategy attempt.
     *
     * If [expectedIdentity] doesn't match what's currently focused, the engine
     * returns a NotFocused failure WITHOUT touching the wrong field.
     */
    suspend fun inject(
        text: String,
        expectedIdentity: SemanticIdentity,
        readinessTimeoutMs: Long = 1_500L,
        perStrategyVerifyTimeoutMs: Long = 1_500L
    ): InjectionResult = scheduler.run(ExecutionLane.INJECTION, "inject:${expectedIdentity::class.simpleName}") {

        val started = System.currentTimeMillis()
        val attempts = mutableListOf<StrategyAttempt>()

        Log.i(TAG, "INJECTION_STARTED text='${text.take(20)}' identity=${expectedIdentity::class.simpleName} pkg=${expectedIdentity.packageId}")
        bus.publish(AgentEvent.Executor.InjectionStarted(
            executorName = "InjectionEngine",
            strategy = "cascade",
            targetIdentity = expectedIdentity::class.simpleName ?: "Unknown",
            payload = text
        ))

        // Step 1: await readiness.
        // Use the weaker, faster-resolving awaitInjectable check instead of
        // awaitReady. Rationale: WhatsApp-class apps don't fire TextSelectionChanged
        // for several seconds after IME slide-up, so the strict readiness gate
        // times out. awaitInjectable resolves on (imeVisible + editable identity
        // matched + foreground matched) which fires within ~150ms.
        val ready = imeCoordinator.awaitInjectable(
            expectedPackage = expectedIdentity.packageId,
            timeoutMs = readinessTimeoutMs
        )
        if (!ready) {
            val r = InjectionResult.Failed(
                reason = "not_injectable_within_${readinessTimeoutMs}ms",
                attempts = emptyList(),
                durationMs = System.currentTimeMillis() - started
            )
            Log.w(TAG, "INJECTION_ABORT_NO_READINESS ${r.reason}")
            bus.publish(AgentEvent.Executor.InjectionFailed(
                executorName = "InjectionEngine",
                strategy = "cascade",
                targetIdentity = expectedIdentity::class.simpleName ?: "Unknown",
                reason = r.reason
            ))
            return@run r
        }

        // Step 2: snapshot clipboard for restoration.
        val clipboardStrategy = cascade.clipboardStrategy()
        val savedClipboard = clipboardStrategy.saveClipboard()

        try {
            // Step 3: cascade.
            val strategies = cascade.strategies()
            for ((idx, strategy) in strategies.withIndex()) {
                val attemptStart = System.currentTimeMillis()
                val attempt = tryStrategy(
                    strategy = strategy,
                    text = text,
                    expectedIdentity = expectedIdentity,
                    verifyTimeoutMs = perStrategyVerifyTimeoutMs
                )
                attempts += attempt
                Log.i(TAG, "ATTEMPT[$idx] ${attempt.strategy} mechanical=${attempt.mechanicalSuccess} " +
                        "verified=${attempt.verified} conf=%.2f dur=${attempt.durationMs}ms notes='${attempt.notes}'"
                            .format(attempt.confidence))

                if (attempt.verified) {
                    val r = InjectionResult.Verified(
                        viaStrategy = attempt.strategy,
                        finalConfidence = attempt.confidence,
                        attempts = attempts.toList(),
                        durationMs = System.currentTimeMillis() - started
                    )
                    bus.publish(AgentEvent.Executor.InjectionSucceeded(
                        executorName = "InjectionEngine",
                        strategy = attempt.strategy,
                        targetIdentity = expectedIdentity::class.simpleName ?: "Unknown",
                        confidence = attempt.confidence
                    ))
                    Log.i(TAG, "INJECTION_VERIFIED via=${attempt.strategy} totalDur=${r.durationMs}ms")
                    return@run r
                }
            }

            // All strategies exhausted without verification.
            val r = InjectionResult.Failed(
                reason = "all_strategies_exhausted",
                attempts = attempts.toList(),
                durationMs = System.currentTimeMillis() - started
            )
            bus.publish(AgentEvent.Executor.InjectionFailed(
                executorName = "InjectionEngine",
                strategy = "cascade",
                targetIdentity = expectedIdentity::class.simpleName ?: "Unknown",
                reason = r.reason
            ))
            Log.w(TAG, "INJECTION_FAILED all_strategies_exhausted attempts=${attempts.size}")
            return@run r

        } finally {
            // Step 5: clipboard restoration (best-effort).
            clipboardStrategy.restoreClipboard(savedClipboard)
        }
    }

    /**
     * Try a single strategy: acquire fresh node, verify identity, inject,
     * verify mutation.
     */
    private suspend fun tryStrategy(
        strategy: InjectionStrategy,
        text: String,
        expectedIdentity: SemanticIdentity,
        verifyTimeoutMs: Long
    ): StrategyAttempt {
        val attemptStart = System.currentTimeMillis()

        // Acquire fresh focused-editable node.
        val svc = PerceptionService.get() ?: return StrategyAttempt(
            strategy = strategy.name,
            mechanicalSuccess = false,
            confidence = 0f,
            verified = false,
            durationMs = System.currentTimeMillis() - attemptStart,
            notes = "perception_unavailable"
        )

        val node: AccessibilityNodeInfo = try {
            svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (t: Throwable) {
            null
        } ?: return StrategyAttempt(
            strategy = strategy.name,
            mechanicalSuccess = false,
            confidence = 0f,
            verified = false,
            durationMs = System.currentTimeMillis() - attemptStart,
            notes = "no_focused_node"
        )

        // Identity match: ensure WorldState still reports our expected identity.
        // If not, the user moved on or focus drifted — DO NOT inject.
        val currentIdentity = store.current().focusedEditableIdentity
        val sameType = currentIdentity?.let { it::class == expectedIdentity::class } == true
        val samePkg = currentIdentity?.packageId == expectedIdentity.packageId
        if (!sameType || !samePkg) {
            try { node.recycle() } catch (_: Throwable) {}
            return StrategyAttempt(
                strategy = strategy.name,
                mechanicalSuccess = false,
                confidence = 0f,
                verified = false,
                durationMs = System.currentTimeMillis() - attemptStart,
                notes = "identity_mismatch_now=${currentIdentity?.let { it::class.simpleName + "/" + it.packageId }}"
            )
        }

        // Track generation BEFORE the strategy runs. If RootGeneration advances
        // during inject() (Compose recomposition, scroll, etc.), the node our
        // strategy used is now stale and we cannot trust the read-back without
        // re-acquiring. ConfidenceEngine handles this by polling refresh().
        val genBefore = RootGeneration.current()

        // Run the strategy.
        val mechanicalSuccess = strategy.inject(node, text, expectedIdentity)
        val mechMs = System.currentTimeMillis() - attemptStart

        if (!mechanicalSuccess) {
            try { node.recycle() } catch (_: Throwable) {}
            return StrategyAttempt(
                strategy = strategy.name,
                mechanicalSuccess = false,
                confidence = 0f,
                verified = false,
                durationMs = mechMs,
                notes = "mechanical_rejected"
            )
        }

        // Verify via ConfidenceEngine.
        val verify = try {
            confidence.verify(
                node = node,
                expectedText = text,
                packageId = expectedIdentity.packageId,
                timeoutMs = verifyTimeoutMs
            )
        } catch (t: Throwable) {
            ConfidenceEngine.VerifyResult(0f, false, 0L, "verify_threw:${t.message}")
        }

        try { node.recycle() } catch (_: Throwable) {}

        return StrategyAttempt(
            strategy = strategy.name,
            mechanicalSuccess = true,
            confidence = verify.confidence,
            verified = verify.verified,
            durationMs = System.currentTimeMillis() - attemptStart,
            notes = buildString {
                append("genBefore=$genBefore genAfter=${RootGeneration.current()};")
                if (verify.notes.isNotEmpty()) append(verify.notes)
            }
        )
    }

    companion object {
        private const val TAG = "InjectionEngine"
    }
}

/**
 * Result of an injection attempt.
 */
sealed class InjectionResult {

    abstract val attempts: List<StrategyAttempt>
    abstract val durationMs: Long

    data class Verified(
        val viaStrategy: String,
        val finalConfidence: Float,
        override val attempts: List<StrategyAttempt>,
        override val durationMs: Long
    ) : InjectionResult()

    data class Failed(
        val reason: String,
        override val attempts: List<StrategyAttempt>,
        override val durationMs: Long
    ) : InjectionResult()
}