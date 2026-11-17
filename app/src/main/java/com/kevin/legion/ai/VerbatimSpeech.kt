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

    /** Start with the balance tool alone (coordinator, 2026-11-17). */
    val TOOLS: Set<String> = setOf("get_balance")

    /** The words to speak from a result the cascade received, or null. */
    fun of(response: JSONObject?): String? {
        if (response == null || response.optString(FROM) !in TOOLS) return null
        return response.optString(KEY).trim().takeIf { it.isNotEmpty() }
    }

    /** From a routed tool's raw output (a JSON string), when [tool] may carry it. */
    fun fromToolOutput(tool: String, output: String?): String? {
        val json = output?.takeIf { tool in TOOLS }?.let { runCatching { JSONObject(it) }.getOrNull() }
        return json?.optString(KEY)?.trim()?.takeIf { it.isNotEmpty() && json.optString(FROM) == tool }
    }
}
