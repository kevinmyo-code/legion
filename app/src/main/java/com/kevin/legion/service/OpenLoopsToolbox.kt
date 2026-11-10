package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.openloops.OpenLoopsController
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Answering a proposed to-do by voice (ADR 0085), with **no new tool declaration**: the setup
 * payload is at its ceiling, and `manage_item` already means "add this to my list" and "take this
 * off it". The brief line ([com.kevin.legion.openloops.OpenLoopsBrief]) tells the model that a yes
 * is `manage_item` add with the task's words and a no is `manage_item` remove.
 *
 * So `manage_item` add or remove whose item names a PENDING proposal (read from the engine now,
 * [OpenLoopsController.match]) answers that proposal through the controller the Proposed to-dos
 * rows call (ADR 0035): add tracks it (the engine makes the reminder or the to-do, so it is never
 * added twice), remove dismisses it for good. Anything that names no proposal falls through to
 * `manage_item` exactly as before (null), so a plain "add milk" is untouched.
 *
 * **Refused in a read-through turn**, the guard `watch` set and `memory` remember use: a turn that
 * read mail, the web, a camera frame or a spoken brief (and the turn after one) cannot answer a
 * proposal, because text read aloud must never decide what is tracked or dropped. The refusal says
 * where the answer can be given instead. Several proposals matching: ask which, change nothing.
 */
object OpenLoopsToolbox {

    /** Test seam. Production is [OpenLoopsController.forContext]. */
    internal var controllerFactory: (Context) -> OpenLoopsController = OpenLoopsController::forContext

    const val READ_THROUGH_REFUSAL =
        "Nothing was tracked or dropped: this turn read outside text (mail, the web, a camera look or " +
            "a spoken brief), so it cannot answer a proposed to-do. Ask him to say it again, or he " +
            "can tap Track or Dismiss in Settings, Proactive speech."

    /** Null when [name]/[args] is not an answer to a proposal, so [LiveToolbox.dispatch] carries on. */
    @Suppress("ReturnCount") // each early null is "not an answer to a proposal; manage_item as before"
    suspend fun dispatch(
        context: Context,
        name: String,
        args: JSONObject,
        touchedReadThroughToolThisTurn: Boolean,
    ): JSONObject? {
        if (name != "manage_item") return null
        val action = args.optString("action").trim().lowercase()
        if (action != "add" && action != "remove") return null
        val item = args.optString("item").trim()
        if (item.isEmpty()) return null
        // An add that asks for more than the proposal (a time, an appointment, a repeat) is an
        // ordinary manage_item add; the engine then closes the proposal as already tracked.
        if (action == "add" && SHAPED_ADD.any { args.optString(it).isNotBlank() }) return null
        if (action == "add" && args.optString("kind").trim().equals("appointment", ignoreCase = true)) return null
        return answer(controllerFactory(context), action, item, args.optString("date").trim(),
            touchedReadThroughToolThisTurn)
    }

    @Suppress("ReturnCount") // no match, ambiguous, read-through, no, a bad date: each answers in words
    internal suspend fun answer(
        controller: OpenLoopsController,
        action: String,
        item: String,
        dateArg: String,
        readThrough: Boolean,
    ): JSONObject? {
        val proposal = when (val m = controller.match(item)) {
            OpenLoopsController.Match.None -> return null
            is OpenLoopsController.Match.Many -> return result(
                false,
                "Nothing was changed. Which one? " + m.proposals.joinToString(", ") { it.title },
            )
            is OpenLoopsController.Match.One -> m.proposal
        }
        if (readThrough) return result(false, READ_THROUGH_REFUSAL)
        if (action == "remove") return controller.dismiss(proposal.id).let { result(it.ok, it.words) }
        val date = if (dateArg.isEmpty()) {
            null
        } else {
            try {
                LocalDate.parse(dateArg)
            } catch (_: DateTimeParseException) {
                return result(false, "Nothing was tracked: I couldn't read \"$dateArg\" as a date - try yyyy-MM-dd.")
            }
        }
        return controller.track(proposal.id, date).let { result(it.ok, it.words) }
    }

    private val SHAPED_ADD = listOf(
        "time", "repeat_kind", "repeat_every", "repeat_days", "repeat_month", "repeat_day",
        "repeat_end_kind", "repeat_end_date", "repeat_end_count",
    )

    private fun result(success: Boolean, message: String): JSONObject =
        JSONObject().put("success", success).put("message", message)
}
