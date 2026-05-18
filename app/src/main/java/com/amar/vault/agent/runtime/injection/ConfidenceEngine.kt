package com.amar.vault.agent.runtime.injection

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.amar.vault.agent.runtime.events.AccessibilityEventBus
import com.amar.vault.agent.runtime.events.AgentEvent
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Probabilistic verification: aggregates multiple signals into a confidence
 * score to detect false-positive injections.
 *
 * Signals (from the architecture doc):
 *   - Text mutation observed via TextChanged event (+0.7)
 *   - Cursor / selection moved via TextSelectionChanged (+0.4)
 *   - Read-back from the node matches injected text (+0.5)
 *   - Search results / structural change observed (deferred to Step 11)
 *
 * Threshold: >= 1.0 = verified. (Architecture doc number; tuneable.)
 *
 * Why probabilistic:
 *   Boolean verification (`text == injected`) breaks under:
 *     - Apps that sanitize input (trim whitespace, normalize unicode)
 *     - Password fields where read-back is masked
 *     - Compose recomposition where the read-back races the apply
 *   Multiple weak signals are more robust than one strong signal.
 *
 * Threading:
 *   verify() suspends until either the signals reach threshold or the
 *   timeout fires. Events arrive via bus subscription; node read-back is
 *   polled at intervals because there's no event for "your read-back is
 *   ready" — the node simply has the new text or doesn't.
 */
@Singleton
class ConfidenceEngine @Inject constructor(
    private val bus: AccessibilityEventBus
) {

    /**
     * Watch for evidence that [expectedText] was actually injected into
     * [node]. Returns a [VerifyResult] with the final confidence and
     * a verdict (verified or not).
     *
     * The watch period is [timeoutMs] from invocation. We accumulate
     * confidence from any signal observed in that window. As soon as
     * we cross the threshold we return early.
     */
    suspend fun verify(
        node: AccessibilityNodeInfo,
        expectedText: String,
        packageId: String?,
        timeoutMs: Long = 1_500L
    ): VerifyResult {
        var confidence = 0.0f
        var observedTextChange = false
        var observedSelectionChange = false
        var observedReadback = false
        var notes = StringBuilder()

        val started = System.currentTimeMillis()

        // Subscribe to relevant signal events from the bus, in parallel.
        val textFlow = bus.subscribe<AgentEvent.Accessibility.TextChanged>()
        val selFlow  = bus.subscribe<AgentEvent.Accessibility.TextSelectionChanged>()

        val result = withTimeoutOrNull(timeoutMs) {
            // Two ways we can cross threshold:
            //   1. Read-back of node text matches expected — quick poll at start
            //   2. Bus events keep arriving (TextChanged + TextSelectionChanged)
            //
            // We do a quick initial read-back, then watch the bus for any
            // additional signals. The merge() combines both flows into one.

            // Initial read-back attempt — sometimes ACTION_SET_TEXT applies
            // synchronously and the read is immediate.
            // Normalize to strip zero-width chars some apps prepend.
            val readback = readBack(node)
            val readbackNorm = normalize(readback)
            val expectedNorm = normalize(expectedText)
            if (readback != null && readbackNorm == expectedNorm) {
                confidence += READBACK_WEIGHT
                observedReadback = true
                notes.append("readback_match;")
            } else if (readback != null && expectedNorm.isNotEmpty() && readbackNorm.contains(expectedNorm)) {
                confidence += READBACK_PARTIAL_WEIGHT
                observedReadback = true
                notes.append("readback_partial='${readback.take(20)}';")
            }

            if (confidence >= THRESHOLD) return@withTimeoutOrNull confidence

            // Watch the bus. Collect until either threshold reached or
            // timeout (withTimeoutOrNull above cancels us).
            //
            // We intentionally DON'T use takeWhile/first chain — that pattern
            // throws NoSuchElementException when the flow completes without
            // emitting (which happens when takeWhile terminates after a
            // side-effect that crosses threshold). Plain collect() with manual
            // break-out via a return is correct here.
            val combined = merge(
                textFlow.onEach { ev ->
                    if (!observedTextChange && ev.packageId == packageId) {
                        val txt = ev.afterText
                        val txtNorm = normalize(txt)
                        if (txt != null && (txtNorm == expectedNorm || txtNorm.contains(expectedNorm))) {
                            confidence += TEXT_MUTATION_WEIGHT
                            observedTextChange = true
                            notes.append("text_event_match;")
                        } else if (txt != null && txt.isNotEmpty()) {
                            confidence += TEXT_MUTATION_PARTIAL_WEIGHT
                            observedTextChange = true
                            notes.append("text_event_any;")
                        }
                    }
                },
                selFlow.onEach { ev ->
                    if (!observedSelectionChange && ev.packageId == packageId) {
                        confidence += SELECTION_WEIGHT
                        observedSelectionChange = true
                        notes.append("selection_event;")
                    }
                }
            )

            try {
                combined.collect {
                    if (confidence >= THRESHOLD) {
                        throw kotlinx.coroutines.CancellationException("threshold_reached")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Expected — we use cancellation to break the collect.
            }

            confidence
        }

        // Final read-back attempt — if events fired but we didn't poll for
        // confirmation, check the node once more.
        if (!observedReadback) {
            val readback = readBack(node)
            val readbackNorm = normalize(readback)
            val expectedNorm = normalize(expectedText)
            if (readback != null && (readbackNorm == expectedNorm || readbackNorm.contains(expectedNorm))) {
                confidence += READBACK_PARTIAL_WEIGHT
                notes.append("late_readback='${readback.take(20)}';")
            }
        }

        val verified = confidence >= THRESHOLD
        val dur = System.currentTimeMillis() - started
        Log.i(TAG, "VERIFY confidence=%.2f verified=$verified dur=${dur}ms notes=$notes"
            .format(confidence))

        return VerifyResult(
            confidence = confidence,
            verified = verified,
            durationMs = dur,
            notes = notes.toString()
        )
    }

    private fun readBack(node: AccessibilityNodeInfo): String? = try {
        node.refresh()
        node.text?.toString()
    } catch (t: Throwable) {
        null
    }

    /**
     * Normalize text for comparison: strips zero-width spaces, BOMs, and
     * leading/trailing whitespace. Necessary because WhatsApp's "Ask Meta
     * AI or Search" field prepends a zero-width space (U+200B) to user
     * input as a placeholder marker. Raw equality would always miss.
     */
    private fun normalize(s: String?): String {
        if (s == null) return ""
        return s
            .replace("\u200B", "")  // ZERO WIDTH SPACE
            .replace("\u200C", "")  // ZERO WIDTH NON-JOINER
            .replace("\u200D", "")  // ZERO WIDTH JOINER
            .replace("\uFEFF", "")  // BOM / ZERO WIDTH NO-BREAK SPACE
            .trim()
    }

    data class VerifyResult(
        val confidence: Float,
        val verified: Boolean,
        val durationMs: Long,
        val notes: String
    )

    companion object {
        private const val TAG = "ConfidenceEngine"

        // Weights from architecture doc, slightly tuned.
        private const val TEXT_MUTATION_WEIGHT          = 0.7f
        private const val TEXT_MUTATION_PARTIAL_WEIGHT  = 0.3f
        private const val SELECTION_WEIGHT              = 0.4f
        private const val READBACK_WEIGHT               = 0.7f
        private const val READBACK_PARTIAL_WEIGHT       = 0.4f

        const val THRESHOLD: Float = 1.0f
    }
}