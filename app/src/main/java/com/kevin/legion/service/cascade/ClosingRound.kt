package com.kevin.legion.service.cascade

import com.kevin.legion.vehicle.AdapterBinding
import org.json.JSONObject

/**
 * Kevin's ruling D-prime (2026-11-19): the closing model round after a round of writes is always
 * asked for, so a step the model still had to do is never lost. When every tool call of the round
 * before was a completed write that code confirmed aloud ([ScriptedLines.doneLine]) and the closing
 * round returns only text, that text is not spoken or shown: it could only repeat the confirmation
 * (the eval heard it in half the write turns). If the closing round calls a tool, it runs as before
 * and its text is kept. Decided from tool results only, never from anyone's words, in this one
 * function so the engine brain can take the same rule.
 *
 * Text is kept (the model answers as before, [SaidFilter] still applies) when the round before had
 * any read or screen call, any router or specialist (`ask_*`, even one that committed: its own
 * words may carry a part that failed or a question, review round 2 D2), a write that failed, was
 * refused, timed out, did not change anything (`changed: false`), or whose result asks him
 * something (`needs_answer`, `needs_confirmation`, a mail draft's `draft_id`), or the first step of
 * a two-step write (not a write, so it has no facts), or that carries the adapter's one-time note
 * ([carriesMore]).
 */
object ClosingRound {
    /** One call of the round: its write's facts (null for anything that is not a write) and its result. */
    class Call(val fact: ScriptedLines.WriteFact?, val response: JSONObject)

    /** True when a text-only closing round after these calls is not spoken. */
    fun dropsText(calls: List<Call>): Boolean = calls.isNotEmpty() && calls.all { confirmedWrite(it) }

    private fun confirmedWrite(c: Call): Boolean = c.fact?.said == true && !c.fact.router && !carriesMore(c.response)

    /**
     * A result that gives the model something to say beyond the change: a question for him
     * ([LoneWordWriteGuard.asksField]) or the adapter's one-time note ("Looks like the X",
     * [AdapterBinding.NOTE_FIELD]). Its closing text is kept and its names drop no sentence
     * ([GeminiTextLoop]), since that text may name the very car code just said.
     */
    fun carriesMore(response: JSONObject): Boolean =
        LoneWordWriteGuard.asksField(response) || response.has(AdapterBinding.NOTE_FIELD)
}
