package com.kevin.legion.ai

import org.json.JSONObject

/**
 * One local capability an investigating [SubAgent] may call: a read-only data
 * pull the worker model requests by name mid-reasoning. Executors must be
 * fast, compact (aim under ~1000 chars), and never throw to the caller -
 * the loop wraps them, but a thrown executor still costs a round.
 */
class AgentTool(
    val name: String,
    val description: String,
    val params: JSONObject = JSONObject(),      // "properties" object (LiveToolbox.obj shape)
    val required: List<String> = emptyList(),
    val timeoutMs: Long = 5_000,
    val run: suspend (JSONObject) -> String,
)

/** Typed outcome of a worker run, so callers can phrase the right failure. */
sealed class AgentResult {
    data class Success(val text: String) : AgentResult()
    object RateLimited : AgentResult()   // 429
    object KeyInvalid : AgentResult()    // 400+API_KEY_INVALID, 401, 403
    object Overloaded : AgentResult()    // 500, 503
    object Offline : AgentResult()       // IOException after one retry
    object Failed : AgentResult()        // parse failure, blocked, other 4xx
}
