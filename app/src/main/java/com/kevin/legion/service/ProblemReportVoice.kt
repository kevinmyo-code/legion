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
 * **What is filed is always [userText]**, his transcribed or typed words for this turn, never the
 * model's `request`: the worker takes the note as the spec, and a model argument can carry text
 * from mail or a page. With no utterance (an unsolicited raise) nothing is filed. A turn after an
 * outside read never reaches here: [ReadThroughWriteGuard] refuses `claude_session` first.
 */
internal object ProblemReportVoice {
    suspend fun file(controller: ClaudeSessionController, userText: String?): JSONObject {
        val note = userText.orEmpty()
        if (note.isBlank()) return result(false, ProblemReport.EMPTY_NOTE)
        val out = controller.reportProblem(note, ProblemReport.SOURCE_VOICE)
        // `done: false` on a filed report: the engine took the task, nothing is fixed.
        return result(out is ClaudeSendResult.Sent, out.sentence).apply {
            if (out is ClaudeSendResult.Sent) put("task_id", out.created.id).put("done", false)
        }
    }

    private fun result(success: Boolean, message: String): JSONObject =
        JSONObject().put("success", success).put("message", message)
}
