package com.amar.vault.agent.perception

import android.util.Log

/**
 * Semantic verifier for search context.
 *
 * Solves the FALSE POSITIVE VERIFICATION problem where structural changes
 * (e.g., QR scanner opening) are incorrectly interpreted as "search opened".
 *
 * The old approach:
 *   "large UI change" → search opened (WRONG — QR scanner also produces massive change)
 *
 * The new contract:
 *   Verification succeeds ONLY if POSITIVE semantic evidence of search context exists
 *   AND NO disqualifying semantics (scan/qr/camera/payment/upi) appear.
 *
 * This function must be called BEFORE any NAVIGATION → INPUT phase transition
 * to prevent the agent from entering text input mode on the wrong screen.
 */
object SearchContextVerifier {

    private const val TAG = "SearchContextVerifier"

    /**
     * Disqualifying semantic tokens. If ANY of these appear in any visible
     * node's text, contentDescription, resourceId, or hint, the screen is
     * NOT a search context — it's likely a scanner, payment, or camera UI.
     */
    private val NEGATIVE_SEMANTICS = listOf(
        "scan", "qr", "camera", "payment", "upi",
        "barcode", "scanner", "pay now", "paytm",
        "scan & pay", "gpay"
    )

    /**
     * Positive semantic tokens that indicate a search/input context.
     */
    private val POSITIVE_SEARCH_HINTS = listOf(
        "search", "type a message", "type here",
        "ask a question", "find", "look up", "enter name",
        "enter text", "write a message"
    )

    /**
     * Result of search context verification with full diagnostic data.
     */
    data class VerificationResult(
        /** Whether the snapshot passes semantic search verification. */
        val isSearchContext: Boolean,

        /** Count of editable nodes found. */
        val editableCount: Int,

        /** Count of focused editable nodes found. */
        val focusedEditableCount: Int,

        /** Positive signals found (search hints, editable nodes, etc.) */
        val positiveSignals: List<String>,

        /** Negative signals found (scan, qr, camera, etc.) */
        val negativeSignals: List<String>,

        /** Total nodes inspected. */
        val totalNodesInspected: Int,

        /** The candidate index that triggered verification, if applicable. */
        val triggerCandidateIndex: Int? = null
    ) {
        /** Human-readable summary for logging. */
        fun toLogString(): String = buildString {
            append("SEARCH_CONTEXT_VERIFICATION: ")
            append("result=${if (isSearchContext) "PASS" else "FAIL"} ")
            append("editable=$editableCount ")
            append("focusedEditable=$focusedEditableCount ")
            append("nodes=$totalNodesInspected ")
            append("positive=[${positiveSignals.joinToString(",")}] ")
            append("negative=[${negativeSignals.joinToString(",")}]")
            if (triggerCandidateIndex != null) {
                append(" candidate=$triggerCandidateIndex")
            }
        }
    }

    /**
     * Verify that the given snapshot represents a valid search/input context.
     *
     * This function:
     *   1. Traverses ALL visible nodes
     *   2. Inspects text, contentDescription, resourceId, hint, className
     *   3. Scores positive search evidence
     *   4. Scores negative scanner/payment evidence
     *   5. Returns authoritative semantic result
     *
     * Verification succeeds ONLY if:
     *   - At least ONE positive signal exists (editable node, search hint, focused editable)
     *   - AND NO negative signals exist (scan, qr, camera, payment, upi)
     *
     * @param snapshot The UI snapshot to verify.
     * @param candidateIndex Optional index of the candidate that triggered this check.
     * @return VerificationResult with full diagnostic data.
     */
    fun verifySearchContext(
        snapshot: UiSnapshot,
        candidateIndex: Int? = null
    ): VerificationResult {
        val positiveSignals = mutableListOf<String>()
        val negativeSignals = mutableListOf<String>()

        var editableCount = 0
        var focusedEditableCount = 0

        for ((idx, el) in snapshot.elements.withIndex()) {
            // Gather all semantic text from this node
            val semanticTexts = listOfNotNull(
                el.text,
                el.contentDesc,
                el.resourceId,
                el.hint
            ).map { it.lowercase() }

            // --- Score editable nodes ---
            if (el.editable) {
                editableCount++
                positiveSignals += "editable_node[idx=$idx,type=${el.type}]"

                if (el.focused) {
                    focusedEditableCount++
                    positiveSignals += "focused_editable[idx=$idx]"
                }
            }

            // INPUT type elements (EditText/TextInputLayout that may not have editable=true yet)
            if (el.type == UiElementType.INPUT) {
                positiveSignals += "input_type_node[idx=$idx]"
            }

            // --- Score search hints ---
            for (text in semanticTexts) {
                for (hint in POSITIVE_SEARCH_HINTS) {
                    if (text.contains(hint)) {
                        positiveSignals += "search_hint[$hint,idx=$idx]"
                    }
                }

                // --- Score negative/disqualifying semantics ---
                for (neg in NEGATIVE_SEMANTICS) {
                    if (text.contains(neg)) {
                        negativeSignals += "disqualifier[$neg,idx=$idx,text=${text.take(50)}]"
                    }
                }
            }

            // --- Check className for EditText variants ---
            val className = el.resourceId?.lowercase() ?: ""
            if (className.contains("edit") || className.contains("search_input") ||
                className.contains("search_bar") || className.contains("search_src_text")) {
                positiveSignals += "search_resource_id[$className,idx=$idx]"
            }
        }

        // --- Final verdict ---
        val hasPositiveEvidence = editableCount > 0 ||
                focusedEditableCount > 0 ||
                positiveSignals.isNotEmpty()

        val hasNegativeEvidence = negativeSignals.isNotEmpty()

        val isSearchContext = hasPositiveEvidence && !hasNegativeEvidence

        val result = VerificationResult(
            isSearchContext = isSearchContext,
            editableCount = editableCount,
            focusedEditableCount = focusedEditableCount,
            positiveSignals = positiveSignals,
            negativeSignals = negativeSignals,
            totalNodesInspected = snapshot.elements.size,
            triggerCandidateIndex = candidateIndex
        )

        // Always log the full verification result for deterministic visibility
        Log.i(TAG, result.toLogString())

        // Log the detailed VERIFIED_CANDIDATE block
        Log.i(TAG, buildString {
            append("VERIFIED_CANDIDATE: ")
            if (candidateIndex != null) append("candidate=$candidateIndex ")
            append("semanticRole=${if (isSearchContext) "SEARCH" else "UNKNOWN"} ")
            append("verification=${if (hasPositiveEvidence) "SEMANTIC" else "STRUCTURAL_ONLY"} ")
            append("positive_count=${positiveSignals.size} ")
            append("negative_count=${negativeSignals.size}")
        })

        return result
    }

    /**
     * Quick boolean check for use in phase transition guards.
     *
     * Equivalent to: verifySearchContext(snapshot).isSearchContext
     * but with less allocation for hot paths.
     */
    fun isSearchContext(snapshot: UiSnapshot): Boolean {
        var hasEditable = false
        var hasSearchHint = false
        var hasDisqualifier = false

        for (el in snapshot.elements) {
            if (el.editable || el.type == UiElementType.INPUT) {
                hasEditable = true
            }

            val texts = listOfNotNull(el.text, el.contentDesc, el.resourceId, el.hint)
            for (t in texts) {
                val lower = t.lowercase()
                for (neg in NEGATIVE_SEMANTICS) {
                    if (lower.contains(neg)) {
                        hasDisqualifier = true
                        Log.w(TAG, "DISQUALIFIER_HIT: '$neg' found in '${t.take(60)}' — NOT a search context")
                        // Early exit on first disqualifier
                        return false
                    }
                }
                for (hint in POSITIVE_SEARCH_HINTS) {
                    if (lower.contains(hint)) {
                        hasSearchHint = true
                    }
                }
            }
        }

        val result = hasEditable && !hasDisqualifier
        if (!result) {
            Log.w(TAG, "SEARCH_CONTEXT_FAILED: editable=$hasEditable searchHint=$hasSearchHint disqualifier=$hasDisqualifier")
        }
        return result
    }
}
