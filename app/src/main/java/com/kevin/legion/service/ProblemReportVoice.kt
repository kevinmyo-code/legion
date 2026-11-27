package com.kevin.legion.service

import com.kevin.legion.claude.ClaudeSendResult
import com.kevin.legion.claude.ClaudeSessionController
import com.kevin.legion.claude.ProblemReport
import org.json.JSONObject

/**
 * The voice door of "report a problem" (alfred-builds ticket 01): `claude_session`'s `report`
 * action. It calls [ClaudeSessionController.reportProblem], the controller the chat panel's
 * long-press Report calls too (ADR 0035).
 *
 * In a read-through turn (mail, web, a camera look) the model's `request` could be text another
 * person wrote, so only his own utterance is filed there; with none, nothing is. Outside one the
 * model's `request` is used because it is his words, tidied. Success only from a 2xx.
 */
internal object ProblemReportVoice {
    const val NEEDS_OWN_WORDS =
        "Nothing was reported. I won't file a problem from words I just read out of mail, a page or a " +
            "camera look. Say what was wrong in your own words and I will."

    suspend fun file(
        controller: ClaudeSessionController,
        request: String,
        userText: String?,
        readThrough: Boolean,
    ): JSONObject {
        val note = if (readThrough) userText.orEmpty() else request
        if (note.isBlank()) {
            return result(false, if (readThrough) NEEDS_OWN_WORDS else ProblemReport.EMPTY_NOTE)
        }
        val out = controller.reportProblem(note, ProblemReport.SOURCE_VOICE)
        // `done: false` on a filed report: the engine took the task, nothing is fixed.
        return result(out is ClaudeSendResult.Sent, out.sentence).apply {
            if (out is ClaudeSendResult.Sent) put("task_id", out.created.id).put("done", false)
        }
    }

    private fun result(success: Boolean, message: String): JSONObject =
        JSONObject().put("success", success).put("message", message)
}
