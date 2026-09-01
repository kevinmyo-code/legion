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

/**
 * Typed outcome of a worker run, so callers can phrase the right failure.
 *
 * [Success.mutatingToolsCalled] (2026-08-17, defect trace: a driver's spoken workout sets during
 * a real 21:42-21:45 session never reached `workout_set_logs`, yet the app told him it was
 * recorded) is the loop's own honest account of which of the caller-nominated mutating tool names
 * actually got dispatched before it produced this text - see [SubAgent.investigate]'s
 * `mutatingToolNames` parameter. Every call that never passes `mutatingToolNames` (all of [ask]/
 * [askTyped]/[askWithUsage], and every [investigate] caller that predates this field) gets the
 * default `emptyList()`, which is the CORRECT value for them too: a one-shot [ask]/[askTyped] call
 * has no tool loop to have called anything in, so "nothing tracked" and "nothing mutated" agree.
 * Defaulted so this is purely additive - no existing `AgentResult.Success(text)` call site changes
 * shape or behavior.
 *
 * This field alone does NOT make [investigate] refuse a write that didn't happen - see
 * [com.kevin.legion.service.LiveToolbox.agentResult]'s `requireMutation` parameter and its own
 * doc comment for why that gate stays off at every current call site: LiveToolbox has no reliable,
 * non-guessing way to know a given driver question was write-shaped (the `question` argument is
 * free prose), and refusing based on a text guess risks breaking a legitimate read - CLAUDE.md's
 * "no false success" cuts both ways, a false REFUSAL on a real read is its own failure mode. This
 * plumbing exists so a FUTURE caller that DOES have a real write-shaped signal (a dedicated
 * single-purpose write tool, or a dispatcher schema extended with an explicit intent argument the
 * model itself declares) can flip that gate on without touching [SubAgent] again.
 *
 * [Success.truncated] (voice-notes ticket 03) is true when the response's own `finishReason` came
 * back `MAX_TOKENS` - the model ran out of output budget before it was done, which for
 * [SubAgent.askTyped]'s structured-output callers means the JSON itself is very likely
 * unterminated. Defaulted `false` so every existing `AgentResult.Success(text)` call site is
 * unchanged; only [SubAgent.askTyped] ever sets it true. A caller ignoring this field behaves
 * exactly as it did before the field existed - it is opt-in signal, not a new obligation.
 */
sealed class AgentResult {
    data class Success(
        val text: String,
        val mutatingToolsCalled: List<String> = emptyList(),
        val truncated: Boolean = false,
    ) : AgentResult()
    object RateLimited : AgentResult()   // 429
    object KeyInvalid : AgentResult()    // 400+API_KEY_INVALID, 401, 403
    object Overloaded : AgentResult()    // 500, 503
    object Offline : AgentResult()       // IOException after one retry
    object Failed : AgentResult()        // parse failure, blocked, other 4xx
}
