package com.amar.vault.agent.intent

import android.util.Log
import com.amar.vault.agent.brain.PlanContext
import com.amar.vault.agent.brain.PrimaryBrain
import com.amar.vault.agent.capability.CapabilityRouter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Layer 1: Intent + Entity Extraction.
 *
 * Two-stage pipeline:
 *
 *   Stage 1 — SimpleIntentExtractor (regex, ~5ms, deterministic)
 *     Catches "<verb> <query> on <app>", "<app> se <query> mangao", etc.
 *     If a pattern matches AND the resolved app is installed, return the
 *     ParsedIntent immediately. No LLM call.
 *
 *   Stage 2 — Brain NLU (LLM, ~2-5s, fuzzy)
 *     Used when the regex extractor returns null. Handles novel phrasings,
 *     ambiguous structure, and multi-clause inputs.
 *
 * Why two stages:
 *   Gemma 3 1B prompt-locks on small models — when given many examples
 *   in a single prompt, it tends to copy from the nearest example rather
 *   than understand the structure. For "<verb> <query> on <app>" patterns
 *   the regex is faster, more reliable, and immune to prompt-locking.
 *   The LLM is held back for cases that genuinely need flexibility.
 *
 * Output guarantees:
 *   Either path returns a ParsedIntent with all invariants enforced,
 *   or returns null. No malformed output ever escapes this class.
 */
@Singleton
class IntentEntityParser @Inject constructor(
    private val brain: PrimaryBrain,
    private val simpleExtractor: SimpleIntentExtractor
) {

    /**
     * Parse user input into a structured intent.
     */
    suspend fun parse(userInput: String): ParsedIntent? {
        if (userInput.isBlank()) return null

        // Stage 1: regex slot extraction
        simpleExtractor.extract(userInput)?.let { parsed ->
            Log.i(TAG, "regex extracted: $parsed")
            return parsed
        }

        // Stage 2: LLM NLU
        Log.i(TAG, "regex returned null, falling through to brain for: \"$userInput\"")
        return parseViaBrain(userInput)
    }

    private suspend fun parseViaBrain(userInput: String): ParsedIntent? {
        val request = buildRequest(userInput)

        val update = runCatching {
            brain.plan(request, PlanContext.Empty).first {
                it is com.amar.vault.agent.brain.PlanUpdate.Completed ||
                        it is com.amar.vault.agent.brain.PlanUpdate.Failed
            }
        }.getOrElse { return null }

        val rawOutput = when (update) {
            is com.amar.vault.agent.brain.PlanUpdate.Completed -> update.rawOutput
            is com.amar.vault.agent.brain.PlanUpdate.Failed    -> update.rawOutput
            else -> return null
        }

        Log.i(TAG, "brain raw output: ${rawOutput.take(200)}")
        return parseJson(rawOutput)
    }

    private fun buildRequest(userInput: String): String {
        return """
Extract structured intent from user input. Output ONLY this JSON:

{"intent":"<INTENT>","app":"<AppName>","query":"<query or null>"}

Allowed intents:
- open_app      (open / launch / start an app)
- search_app    (search / find / play / watch / type something inside an app)
- order_item    (buy / order / mangao / delivery request)

Rules:
- intent: must be EXACTLY one of the three values above
- app: natural name like "WhatsApp", "YouTube", "Blinkit" (never package ids)
- query: the thing being searched/ordered, or null

Examples:
User: "open whatsapp"
JSON: {"intent":"open_app","app":"WhatsApp","query":null}

User: "open blinkit"
JSON: {"intent":"open_app","app":"Blinkit","query":null}

User: "play despacito on youtube"
JSON: {"intent":"search_app","app":"YouTube","query":"despacito"}

User: "search shoes on amazon"
JSON: {"intent":"search_app","app":"Amazon","query":"shoes"}

User: "order chips from blinkit"
JSON: {"intent":"order_item","app":"Blinkit","query":"chips"}

User: "blinkit se chips mangao"
JSON: {"intent":"order_item","app":"Blinkit","query":"chips"}

User: "whatsapp kholo"
JSON: {"intent":"open_app","app":"WhatsApp","query":null}

User: "youtube pe gana chala"
JSON: {"intent":"search_app","app":"YouTube","query":"gana"}

User: "open blinkit and type chips"
JSON: {"intent":"search_app","app":"Blinkit","query":"chips"}

User: "${userInput.replace("\"", "\\\"")}"
JSON:
        """.trimIndent()
    }

    private fun parseJson(raw: String): ParsedIntent? {
        val jsonStr = extractFirstJsonObject(raw) ?: return null

        val obj: JsonObject = runCatching {
            PARSER_JSON.parseToJsonElement(jsonStr).jsonObject
        }.getOrElse { return null }

        val intentStr = obj["intent"]?.jsonPrimitive?.contentOrNull()?.trim() ?: return null
        val app = obj["app"]?.jsonPrimitive?.contentOrNull()?.trim()
        val queryRaw = obj["query"]?.jsonPrimitive?.contentOrNull()?.trim()

        if (app.isNullOrBlank()) return null

        val query = when {
            queryRaw == null -> null
            queryRaw.isBlank() -> null
            queryRaw.equals("null", ignoreCase = true) -> null
            else -> queryRaw
        }

        val intent = IntentType.fromWire(intentStr) ?: return null

        when (intent) {
            IntentType.OPEN_APP -> {
                if (query != null) {
                    return ParsedIntent(IntentType.SEARCH_APP, app, query)
                }
            }
            IntentType.SEARCH_APP, IntentType.ORDER_ITEM -> {
                if (query == null) return null
            }
        }

        return ParsedIntent(intent, app, query)
    }

    private fun extractFirstJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escape = false

        for (i in start until raw.length) {
            val c = raw[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        if (this.isString) this.content else this.content.takeIf { it != "null" }

    companion object {
        private const val TAG = "IntentEntityParser"

        private val PARSER_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }
}

enum class IntentType(val wire: String) {
    OPEN_APP("open_app"),
    SEARCH_APP("search_app"),
    ORDER_ITEM("order_item");

    companion object {
        fun fromWire(s: String): IntentType? = entries.firstOrNull {
            it.wire.equals(s, ignoreCase = true)
        }
    }
}

@Serializable
data class ParsedIntent(
    val intent: IntentType,
    val app: String,
    val query: String?
) {
    init {
        require(app.isNotBlank()) { "app cannot be blank" }
        when (intent) {
            IntentType.OPEN_APP -> require(query == null) {
                "open_app must not carry a query"
            }
            IntentType.SEARCH_APP, IntentType.ORDER_ITEM -> require(!query.isNullOrBlank()) {
                "$intent requires a non-blank query"
            }
        }
    }
}