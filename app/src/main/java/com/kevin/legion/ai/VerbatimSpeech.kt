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
    val TOOLS: Set<String> = setOf(BALANCE, DIAGNOSTICS, BUDGET)

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

    /** From a routed tool's raw output (a JSON string), when [tool] may carry it. */
    fun fromToolOutput(tool: String, output: String?): String? {
        val json = output?.takeIf { tool in TOOLS }?.let { runCatching { JSONObject(it) }.getOrNull() }
        return json?.optString(KEY)?.trim()?.takeIf { it.isNotEmpty() && json.optString(FROM) == tool }
    }
}
