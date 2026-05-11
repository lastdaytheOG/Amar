package com.amar.vault.agent.intent

import com.amar.vault.agent.dsl.ActionEnvelope
import com.amar.vault.agent.dsl.AgentAction
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Fast-path intent router. Pure regex/string matching plus a deterministic
 * fuzzy resolver against [AppIndex]. No LLM, no JSON, no executor changes.
 *
 * Pipeline for "open <X>" / "launch <X>" / "khol <X>":
 *   1. Strip the verb prefix to get the raw app query.
 *   2. Normalize.
 *   3. Tier A — exact variant match (curated aliases + label + compact form).
 *   4. Tier B — token-overlap.
 *   5. Tier C — partial-contains.
 *   6. Tier D — Levenshtein fuzzy.
 *
 * If best confidence is below ACCEPT_THRESHOLD, returns null (caller falls
 * back to LLM).
 */
object IntentRouter {

    fun route(rawInput: String): ActionEnvelope? {
        val input = normalizeInput(rawInput)
        if (input.isBlank()) return null

        if (input == "home" || input == "go home") {
            return ActionEnvelope(action = AgentAction.Home)
        }

        val appQuery = extractOpenQuery(input) ?: return null
        if (appQuery.isBlank()) return null

        val resolved = resolveApp(appQuery) ?: return null
        return ActionEnvelope(
            action = AgentAction.OpenApp(
                app = resolved.app.label,
                packageId = resolved.app.packageName
            )
        )
    }

    private fun extractOpenQuery(input: String): String? {
        for (regex in OPEN_PATTERNS) {
            val m = regex.matchEntire(input) ?: continue
            return m.groupValues[1].trim()
        }
        return null
    }

    private val OPEN_PATTERNS: List<Regex> = listOf(
        Regex("""^(?:open|launch|start|run|load)\s+(.+?)\s*$"""),
        Regex("""^(?:khol|kholo|chalu|chalao)\s+(.+?)\s*$"""),
        Regex("""^(.+?)\s+(?:kholo|khol)\s*$""")
    )

    private const val ACCEPT_THRESHOLD = 0.60
    private const val EXACT_CONFIDENCE = 1.00
    private const val TOKEN_OVERLAP_BASE = 0.85
    private const val PARTIAL_BASE = 0.70
    private const val FUZZY_BASE = 0.60

    private data class Resolved(val app: IndexedApp, val confidence: Double)

    private fun resolveApp(query: String): Resolved? {
        val q = normalizeQuery(query)
        if (q.isBlank()) return null

        val all = AppIndex.all()
        if (all.isEmpty()) return null

        // Tier A: exact variant
        AppIndex.lookupExact(q)?.let { return Resolved(it, EXACT_CONFIDENCE) }

        val qCompact = q.replace(" ", "")
        if (qCompact != q) {
            AppIndex.lookupExact(qCompact)?.let {
                return Resolved(it, EXACT_CONFIDENCE - 0.02)
            }
        }

        val qTokens = q.split(' ').filter { it.isNotBlank() }

        // Tier B: token-overlap
        val tokenScored = all.mapNotNull { app ->
            val overlap = qTokens.count { qt ->
                app.tokens.any { it == qt || it.startsWith(qt) || qt.startsWith(it) }
            }
            if (overlap == 0) return@mapNotNull null
            val ratio = overlap.toDouble() / qTokens.size
            val confidence = TOKEN_OVERLAP_BASE + (ratio * 0.10).coerceAtMost(0.10)
            Triple(app, confidence, overlap)
        }
        if (tokenScored.isNotEmpty()) {
            val best = tokenScored
                .filter { it.third >= 1 }
                .sortedWith(
                    compareByDescending<Triple<IndexedApp, Double, Int>> { it.second }
                        .thenBy { it.first.normalizedLabel.length }
                        .thenBy { it.first.packageName }
                )
                .firstOrNull()
            if (best != null && best.second >= ACCEPT_THRESHOLD) {
                return Resolved(best.first, best.second)
            }
        }

        // Tier C: partial-contains (4+ char query)
        if (q.length >= 4) {
            val partial = all.asSequence()
                .filter { app ->
                    app.normalizedLabel.contains(q) ||
                            app.variants.any { v -> v.length >= 4 && v.contains(q) } ||
                            app.normalizedLabel.replace(" ", "").contains(qCompact)
                }
                .sortedWith(
                    compareBy<IndexedApp> { it.normalizedLabel.length }
                        .thenBy { it.packageName }
                )
                .firstOrNull()
            if (partial != null) {
                val confidence = PARTIAL_BASE +
                        (q.length.toDouble() / partial.normalizedLabel.length).coerceAtMost(0.15)
                if (confidence >= ACCEPT_THRESHOLD) {
                    return Resolved(partial, confidence)
                }
            }
        }

        // Tier D: Levenshtein fuzzy
        if (q.length >= 4) {
            val maxDist = when {
                q.length <= 5 -> 1
                q.length <= 8 -> 2
                else           -> 3
            }
            val fuzzyBest = all.asSequence()
                .map { app ->
                    val candidates = mutableListOf<Int>()
                    candidates += levenshtein(app.normalizedLabel, q)
                    candidates += levenshtein(app.normalizedLabel.replace(" ", ""), qCompact)
                    for (t in app.tokens) if (t.length >= 3) candidates += levenshtein(t, q)
                    val best = candidates.min()
                    Triple(app, best, app.normalizedLabel.length)
                }
                .filter { it.second <= maxDist }
                .sortedWith(
                    compareBy<Triple<IndexedApp, Int, Int>> { it.second }
                        .thenBy { it.third }
                        .thenBy { it.first.packageName }
                )
                .firstOrNull()
            if (fuzzyBest != null) {
                val app = fuzzyBest.first
                val dist = fuzzyBest.second
                val maxLen = max(app.normalizedLabel.length, q.length).coerceAtLeast(1)
                val similarity = 1.0 - (dist.toDouble() / maxLen)
                val confidence = max(FUZZY_BASE, similarity)
                if (confidence >= ACCEPT_THRESHOLD) {
                    return Resolved(app, confidence)
                }
            }
        }

        return null
    }

    private fun normalizeInput(s: String): String =
        s.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun normalizeQuery(s: String): String =
        s.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j

        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[a.length][b.length]
    }
}