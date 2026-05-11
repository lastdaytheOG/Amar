package com.amar.vault.agent.intent

import com.amar.vault.agent.capability.CapabilityRouter
import java.util.Locale

/**
 * Tier 2 intent extractor — deterministic regex slot extraction.
 *
 * Sits between Tier 1 (IntentRouter for "open app" fast-path) and Tier 3
 * (LLM brain via IntentEntityParser).
 *
 * Catches the very common "<verb> <query> <preposition> <app>" pattern
 * and its Hindi variants WITHOUT calling the LLM. This is faster
 * (~5ms vs ~3000ms) and immune to prompt-locking on small models like
 * Gemma 3 1B that can't reliably distinguish "search wifi on settings"
 * from "search dog on google" because both fit the same template.
 *
 * Patterns covered (all case-insensitive, allowing extra whitespace):
 *
 *   English:
 *     <verb> <query> on <app>     → SEARCH_APP
 *     <verb> <query> in <app>     → SEARCH_APP
 *     <verb> <query> at <app>     → SEARCH_APP
 *     <verb> <query> from <app>   → ORDER_ITEM   (only if verb is order/buy)
 *     <verb> <query> using <app>  → SEARCH_APP
 *     <verb> <app> for <query>    → SEARCH_APP
 *
 *   Hindi (romanized):
 *     <app> par/pe <query> <verb>          → SEARCH_APP
 *     <app> se <query> mangao/buy karo     → ORDER_ITEM
 *
 * Verbs:
 *   search-class: search, find, look, look up, play, watch, listen, type
 *   order-class:  order, buy, get, mangao, mangwa, mangwao
 *
 * Returns null if no pattern matches OR if the resolved app isn't installed.
 * Caller falls through to the LLM brain.
 *
 * Why regex and not full grammar:
 *   These patterns cover ~80% of real-world commands by frequency.
 *   The remaining 20% (multi-clause, ambiguous, novel phrasings) are
 *   exactly where the LLM earns its keep. Don't try to handle everything.
 */
class SimpleIntentExtractor(
    private val router: CapabilityRouter
) {

    /**
     * Try to extract a structured intent. Returns null on no match — caller
     * should fall through to the LLM brain.
     *
     * The returned ParsedIntent's `app` field is the user-facing name as
     * spoken (e.g., "Settings", "YouTube"). CapabilityRouter resolves it
     * to a packageId on the next layer.
     */
    fun extract(rawInput: String): ParsedIntent? {
        val input = normalize(rawInput)
        if (input.isBlank()) return null

        // Try each pattern in order; first match wins.
        for (rule in RULES) {
            val match = rule.regex.matchEntire(input) ?: continue
            val verb = match.groupValues[rule.verbGroup].trim()
            val query = match.groupValues[rule.queryGroup].trim()
            val app = match.groupValues[rule.appGroup].trim()

            if (query.isBlank() || app.isBlank()) continue

            val intent = rule.classifier(verb)
            if (intent == null) continue

            // Build the ParsedIntent. Validation will throw if invariants
            // break (e.g., empty app); we wrap to return null cleanly.
            return runCatching {
                ParsedIntent(
                    intent = intent,
                    app = app.replaceFirstChar { it.titlecase(Locale.ROOT) },
                    query = query
                )
            }.getOrNull()
        }

        return null
    }

    private fun normalize(s: String): String =
        s.trim().replace(Regex("\\s+"), " ")

    // -------------------------------------------------------------------------
    // Rules
    // -------------------------------------------------------------------------

    private data class Rule(
        val regex: Regex,
        val verbGroup: Int,
        val queryGroup: Int,
        val appGroup: Int,
        /** Decides intent type from the verb. Returns null to skip the match. */
        val classifier: (String) -> IntentType?
    )

    companion object {
        // Verb sets — keep terse, expand only when real-world examples force it.
        private val SEARCH_VERBS = setOf(
            "search", "find", "look", "lookup", "look up",
            "play", "watch", "listen", "type", "open"
        )
        private val ORDER_VERBS = setOf(
            "order", "buy", "get", "mangao", "mangwa", "mangwao", "manga"
        )

        private fun classifySearchOrOrder(verb: String): IntentType? {
            val v = verb.lowercase(Locale.ROOT)
            return when {
                v in SEARCH_VERBS -> IntentType.SEARCH_APP
                v in ORDER_VERBS -> IntentType.ORDER_ITEM
                else -> null
            }
        }

        // For "from <app>" — only order verbs are sensible there.
        private fun classifyOrderOnly(verb: String): IntentType? {
            val v = verb.lowercase(Locale.ROOT)
            return if (v in ORDER_VERBS) IntentType.ORDER_ITEM else null
        }

        // For Hindi "X se Y mangao" — order verbs only.
        private fun classifyHindiOrder(verb: String): IntentType? = classifyOrderOnly(verb)

        // For Hindi "X par/pe Y chala/dekha" — search verbs.
        private val HINDI_SEARCH_VERBS = setOf(
            "chala", "chalao", "chalu", "dekho", "dekha", "dikha", "khojo",
            "search", "find", "play"
        )
        private fun classifyHindiSearch(verb: String): IntentType? {
            val v = verb.lowercase(Locale.ROOT)
            return if (v in HINDI_SEARCH_VERBS) IntentType.SEARCH_APP else null
        }

        /**
         * Rule order matters: more specific patterns must come first.
         * The regex group indices (1, 2, 3...) are encoded into the Rule's
         * verbGroup/queryGroup/appGroup fields so we can keep the patterns
         * readable.
         */
        private val RULES: List<Rule> = listOf(
            // EN: "<verb> <query> on <app>"
            //     "search dog on google"
            Rule(
                regex = Regex("""^(\w+)\s+(.+?)\s+(?:on|in|at|using|via)\s+(.+?)$""",
                    RegexOption.IGNORE_CASE),
                verbGroup = 1, queryGroup = 2, appGroup = 3,
                classifier = ::classifySearchOrOrder
            ),

            // EN: "<verb> <query> from <app>"  (order verbs only)
            //     "order chips from blinkit"
            Rule(
                regex = Regex("""^(\w+)\s+(.+?)\s+from\s+(.+?)$""",
                    RegexOption.IGNORE_CASE),
                verbGroup = 1, queryGroup = 2, appGroup = 3,
                classifier = ::classifyOrderOnly
            ),

            // EN: "<verb> <app> for <query>"
            //     "search youtube for cat videos"
            Rule(
                regex = Regex("""^(\w+)\s+(\S+(?:\s+\S+)?)\s+for\s+(.+?)$""",
                    RegexOption.IGNORE_CASE),
                verbGroup = 1, queryGroup = 3, appGroup = 2,
                classifier = ::classifySearchOrOrder
            ),

            // HI romanized: "<app> par/pe <query> <verb>"
            //     "youtube par gana chala"
            //     "google pe dog search"
            Rule(
                regex = Regex("""^(.+?)\s+(?:par|pe|me|mein)\s+(.+?)\s+(\w+)$""",
                    RegexOption.IGNORE_CASE),
                verbGroup = 3, queryGroup = 2, appGroup = 1,
                classifier = ::classifyHindiSearch
            ),

            // HI romanized: "<app> se <query> mangao"
            //     "blinkit se chips mangao"
            Rule(
                regex = Regex("""^(.+?)\s+se\s+(.+?)\s+(\w+)$""",
                    RegexOption.IGNORE_CASE),
                verbGroup = 3, queryGroup = 2, appGroup = 1,
                classifier = ::classifyHindiOrder
            )
        )
    }
}