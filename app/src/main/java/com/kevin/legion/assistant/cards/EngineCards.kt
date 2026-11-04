package com.kevin.legion.assistant.cards

import com.kevin.legion.service.EngineToolAnswer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cards from the household engine's own tool results, reached through `ask_engine`: built from the
 * MCP result's `structuredContent`, never from the result's text or the sub-agent's answer.
 *
 * Only `run_procedure` makes a card today (a steps card). Its structured summary
 * (`procedures/runner.py` `_summary`) carries each step's id, kind, tool and outcome, and the ids
 * that did not run, but **no per-step time**: that lives on the server's `ProcedureStep` row. So
 * [StepItem.ms] is read when present (if the engine starts sending `ms`, the phone shows it with no
 * change) and is otherwise null, shown as "time not reported". [ConversationCard.Steps.totalMs] is
 * the phone's own measured round trip for the call, and the card says so.
 */
object EngineCards {

    /**
     * The engine's own card ([ServerCards], from `_meta.card`) when it sent one; otherwise, for
     * `run_procedure` only, a steps card from its structured summary (an engine from before
     * `_meta.card`). A run that stopped part way is still a card, error flag or not.
     */
    fun fromAnswer(answer: EngineToolAnswer): ConversationCard? =
        answer.card?.let(ServerCards::parse) ?: fromTool(answer.tool, answer.structured, answer.roundTripMs)

    fun fromTool(name: String, structured: JSONObject?, roundTripMs: Long): ConversationCard? =
        when (name) {
            "run_procedure" -> structured?.let { procedureSteps(it, roundTripMs) }
            else -> null
        }

    internal fun procedureSteps(s: JSONObject, roundTripMs: Long): ConversationCard.Steps? {
        val procedure = s.optString("procedure").ifBlank { return null }
        val items = ranSteps(s.optJSONArray("steps") ?: JSONArray()) + notRun(s.optJSONArray("not_run") ?: JSONArray())
        val note = listOfNotNull(
            statusWords(s.optString("status")),
            "Total is the phone's round trip for the whole run; the engine did not report step times."
                .takeIf { items.all { it.ms == null } },
        ).joinToString(" ").ifBlank { null }
        return ConversationCard.Steps(
            title = "Ran ${words(procedure)}",
            name = procedure,
            items = items,
            totalMs = roundTripMs.takeIf { it >= 0 },
            note = note,
        )
    }

    private fun ranSteps(ran: JSONArray): List<StepItem> = (0 until ran.length()).mapNotNull { i ->
        val step = ran.optJSONObject(i) ?: return@mapNotNull null
        val status = when (step.optString("outcome")) {
            "ok" -> StepStatus.OK
            "failed", "refused" -> StepStatus.FAILED
            else -> StepStatus.SKIPPED
        }
        val ms = if (step.has("ms") && !step.isNull("ms")) step.optLong("ms") else null
        StepItem(stepLabel(step.optString("step_id"), step.optString("tool")), status, ms)
    }

    private fun notRun(ids: JSONArray): List<StepItem> =
        (0 until ids.length()).map { StepItem(stepLabel(ids.optString(it), ""), StepStatus.SKIPPED, null) }

    private fun statusWords(status: String): String? = when (status) {
        "needs_answer" -> "Waiting for an answer."
        "partial" -> "Did not finish; some steps ran."
        "failed" -> "Did not finish."
        else -> null
    }

    /** "recalls_lookup" -> "Recalls lookup", with the tool it called in brackets when it called one. */
    private fun stepLabel(stepId: String, tool: String): String {
        val base = words(stepId).replaceFirstChar { it.uppercase() }
        return if (tool.isBlank()) base else "$base ($tool)"
    }

    private fun words(id: String): String = id.replace('_', ' ').trim()
}
