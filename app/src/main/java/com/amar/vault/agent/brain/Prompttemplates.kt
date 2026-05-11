package com.amar.vault.agent.brain

object PromptTemplates {

    const val MAX_CORRECTION_ROUNDS = 2

    val SYSTEM_PROMPT: String = """
You convert user commands into a single JSON object. Output ONLY JSON. No prose.

Two allowed actions:
  open_app    : launch an app
  search_app  : search inside an app (needs query)

Schema:
  {"action":"open_app","params":{"app":"AppName"}}
  {"action":"search_app","params":{"app":"AppName","query":"..."}}

Verbs that mean open_app: open, launch, start, run, khol, kholo, chalu
Verbs that mean search_app: play, search, find, watch, show, type

The user's requested app MUST be copied verbatim into "app". Never substitute a different app.
""".trimIndent()

    val FEW_SHOT_PROMPT: String = """
Examples:
User: open blinkit
JSON: {"action":"open_app","params":{"app":"Blinkit"}}

User: launch chrome
JSON: {"action":"open_app","params":{"app":"Chrome"}}

User: open whatsapp
JSON: {"action":"open_app","params":{"app":"WhatsApp"}}

User: search shoes on amazon
JSON: {"action":"search_app","params":{"app":"Amazon","query":"shoes"}}

User: play despacito on youtube
JSON: {"action":"search_app","params":{"app":"YouTube","query":"despacito"}}
""".trimIndent()

    fun buildPlanPrompt(request: String, context: PlanContext): String {
        return buildString {
            append(SYSTEM_PROMPT)
            append("\n\n")
            append(FEW_SHOT_PROMPT)
            append("\n\nUser: ")
            append(request.trim())
            append("\nJSON:")
        }
    }

    fun buildCorrectionPrompt(
        originalRequest: String,
        originalContext: PlanContext,
        rejectedOutput: String,
        validationReasons: List<String>
    ): String {
        return buildString {
            append(SYSTEM_PROMPT)
            append("\n\n")
            append(FEW_SHOT_PROMPT)
            append("\n\nUser: ")
            append(originalRequest.trim())
            append("\n\nYour previous answer was invalid: ")
            append(rejectedOutput.take(200))
            append("\n\nOutput only correct JSON:\nJSON:")
        }
    }

    fun extractJson(raw: String): String {
        val stripped = raw.trim()
        val start = stripped.indexOf('{')
        if (start == -1) return raw

        var depth = 0
        var inString = false
        var escape = false

        for (i in start until stripped.length) {
            val c = stripped[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) {
                        return stripped.substring(start, i + 1)
                    }
                }
            }
        }
        return raw
    }
}