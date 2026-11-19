package com.kevin.legion.service.cascade

import org.json.JSONObject

/**
 * Kevin's ruling D (2026-11-19): when every tool call of a model round was a completed write that
 * code confirmed aloud ([ScriptedLines.doneLine]), the turn ends after the code line: the model is
 * not asked for a closing round, so there is no closing sentence to repeat the confirmation (the
 * eval heard it in half the write turns). Decided from tool results only, never from anyone's
 * words, in this one function so the engine brain can take the same rule.
 *
 * Not such a round, so the model answers as before (and [SaidFilter] still applies): any read or
 * screen call, any router or specialist (`ask_*`, even one that committed: its own words may carry
 * a part that failed or a question, review round 2 D2), a write that failed, was refused, timed out, did
 * not change anything (`changed: false`), or whose result asks him something (`needs_answer`,
 * `needs_confirmation`, a mail draft's `draft_id`), or the first step of a two-step write (not a
 * write, so it has no facts).
 */
object ClosingRound {
    /** One call of the round: its write's facts (null for anything that is not a write) and its result. */
    class Call(val fact: ScriptedLines.WriteFact?, val response: JSONObject)

    /** True when the round after these calls is not asked for. */
    fun skip(calls: List<Call>): Boolean = calls.isNotEmpty() && calls.all { confirmedWrite(it) }

    private fun confirmedWrite(c: Call): Boolean =
        c.fact?.said == true && !c.fact.router && !LoneWordWriteGuard.asksField(c.response)
}
