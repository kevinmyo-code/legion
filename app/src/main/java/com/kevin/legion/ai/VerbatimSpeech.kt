package com.kevin.legion.ai

import org.json.JSONObject

/**
 * A tool result whose words are SPOKEN by the cascade itself, verbatim, instead of being phrased by
 * the model (stage 7 money gate, 2026-11-17: flash-lite turned code-made amounts into wrong ones,
 * "$1,295.55" for "$1,195.55", and garbled others). Invariant: in a balance answer every money amount
 * he hears is text made by code. Only the tools in [TOOLS] may carry [KEY]; [FROM] names the tool that
 * made it, and the cascade speaks it only when [FROM] is in [TOOLS], whether the tool was called
 * directly or through a router ([SubAgent.route] lifts it from the inner result).
 */
object VerbatimSpeech {
    const val KEY = "speak_verbatim"
    const val FROM = "verbatim_from"

    /** The balance tool (stage 7), the diagnostics reads (2026-11-17), and the budget views below. */
    const val BALANCE = "get_balance"
    const val DIAGNOSTICS = "get_codes"

    /** Budget left, a category's spend, top categories (2026-11-19); its `total` view is not marked. */
    const val BUDGET = "get_monthly_spend"

    /** `claude_session`'s factory board actions only (`where`, `idea`; backlog intake 1122). */
    const val CLAUDE_SESSION = "claude_session"
    val TOOLS: Set<String> = setOf(BALANCE, DIAGNOSTICS, BUDGET, CLAUDE_SESSION)

    /**
     * Marks words the model must never read (the factory board's card titles, which agents write):
     * [forModel] strips them, and [KEY], from the copy the model is handed. The phone still speaks them.
     */
    const val PRIVATE = "verbatim_private"

    /** The words to speak from a result the cascade received, or null. */
    fun of(response: JSONObject?): String? {
        if (response == null || response.optString(FROM) !in TOOLS) return null
        return response.optString(KEY).trim().takeIf { it.isNotEmpty() }
    }

    /** Which allowlisted tool made [response]'s words, when [of] would speak them. */
    fun fromOf(response: JSONObject?): String? = response?.optString(FROM)?.takeIf { of(response) != null }

    /**
     * Marks [result] to be spoken by the phone as [words], made by [from] (a [TOOLS] name). Blank
     * words leave it unmarked. Returns [result].
     */
    fun mark(result: JSONObject, words: String, from: String): JSONObject {
        require(from in TOOLS) { "$from is not on the verbatim allowlist" }
        if (words.isNotBlank()) result.put(KEY, words.trim()).put(FROM, from)
        return result
    }

    /** [mark], and the words kept from the model's copy ([forModel]). Returns [result]. */
    fun markPrivate(result: JSONObject, words: String, from: String): JSONObject =
        mark(result, words, from).apply { if (words.isNotBlank()) put(PRIVATE, true) }

    /** What the model is handed: [response] as it is, or a copy without private spoken words. */
    fun forModel(response: JSONObject): JSONObject {
        if (!response.optBoolean(PRIVATE)) return response
        return unmark(JSONObject(response.toString())).apply { remove(PRIVATE) }
    }

    /** What history keeps in place of private spoken words ([redact]). */
    const val PRIVATE_HISTORY = "(LEGION's own voice read the factory board aloud.)"

    /** The private words [response] carries, or null ([markPrivate]). */
    fun privateWords(response: JSONObject?): String? =
        if (response?.optBoolean(PRIVATE) == true) of(response) else null

    /**
     * [text] as history may keep it: [words] (or the part of them spoken before a cut-off) replaced by
     * [PRIVATE_HISTORY], so the next turn's model and the conversation record never hold them.
     */
    fun redact(text: String, words: String?): String {
        val w = words?.trim().orEmpty()
        val start = if (w.isEmpty()) -1 else text.indexOf(w.take(REDACT_PROBE))
        if (start < 0) return text
        val end = minOf(text.length, start + w.length)
        return (text.substring(0, start) + PRIVATE_HISTORY + text.substring(end)).trim()
    }

    private const val REDACT_PROBE = 24

    /** [result] with its words no longer marked to be spoken by the phone. Returns [result]. */
    fun unmark(result: JSONObject): JSONObject {
        result.remove(KEY)
        result.remove(FROM)
        return result
    }

    /** From a routed tool's raw output (a JSON string), when [tool] may carry it. */
    fun fromToolOutput(tool: String, output: String?): String? {
        val json = output?.takeIf { tool in TOOLS }?.let { runCatching { JSONObject(it) }.getOrNull() }
        return json?.optString(KEY)?.trim()?.takeIf { it.isNotEmpty() && json.optString(FROM) == tool }
    }
}
