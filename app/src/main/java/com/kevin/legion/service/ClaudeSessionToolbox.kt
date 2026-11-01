package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.claude.ClaudeOutcome
import com.kevin.legion.claude.ClaudeSendResult
import com.kevin.legion.claude.ClaudeSessionController
import com.kevin.legion.claude.ClaudeTaskDraft
import com.kevin.legion.claude.ClaudeWatch
import com.kevin.legion.claude.ClaudeWatchStore
import com.kevin.legion.claude.ClaudeWording
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Claude session's voice surface (claude-session ticket 05, ADR 0067) as ONE `claude_session`
 * tool with an `action`, not four: [LiveSetupPayloadSizeTest] holds the Live setup message under a
 * ceiling with almost no headroom, the trade `bought_log` and `books` made. The ticket's four tools
 * are actions here (`send`, `check`, `answer`, `send_file`), plus `assign` (the answer to "which
 * machine?", Kevin 2026-11-01) and `quiet` (the one-instruction silence of the result
 * announcements). `tools/voice_guide.py` and `VoiceGuideDataTest` scan this file.
 *
 * **Every action calls [ClaudeSessionController]**, the controller the Claude session screen calls
 * (ADR 0035). A send is reported as sent or queued only from a 2xx, and never as done: its outcome
 * comes from `check` or the result announcement (CLAUDE.md section 7). Whether to send at all is
 * the prompt's rule (`CLAUDE_SESSION_CLAUSE` in `ai/AriaBrain.kt`): named, or a yes to the offer.
 */
object ClaudeSessionToolbox {

    const val TOOL_NAME = "claude_session"

    /** Test seam: swapped for a controller over a fake backend. */
    internal var controllerFactory: (Context) -> ClaudeSessionController = ClaudeSessionController::forContext

    /** Test seam for `quiet`/`unquiet`: production flips the proactive "Claude tasks" switch. */
    internal var watchFactory: (Context) -> ClaudeWatch = ::ClaudeWatchStore

    private const val DESCRIPTION =
        "Kevin's Claude session on his laptops (Alienware, work laptop). send queues a task; check reads " +
            "machines, tasks and open sessions; answer follows up a task (yes=true only for his yes to its " +
            "question); assign picks the machine it asked for; send_file only after he says yes to having " +
            "a file on his phone; quiet/unquiet stop/restart result announcements. A sent task is not done."

    fun declarations(): JSONArray = JSONArray().put(
        fn(
            name = "claude_session",
            description = DESCRIPTION,
            params = JSONObject()
                .put(
                    "action",
                    prop("string", null, listOf("send", "check", "answer", "assign", "send_file", "quiet", "unquiet")),
                )
                .put("request", prop("string", "The task, or the follow-up, in his words."))
                .put("machine", prop("string", "Only if named."))
                .put("offered", prop("boolean", "True if this is his yes to your offer."))
                .put("email_me", prop("boolean", "True only if he asked to email it to himself."))
                .put("task_id", prop("integer"))
                .put("file_id", prop("integer"))
                .put("yes", prop("boolean")),
            required = listOf("action"),
        ),
    )

    // `name = "..."` written out in the call above, not via TOOL_NAME: tools/voice_guide.py and
    // VoiceGuideDataTest find tool names by scanning for that exact shape.
    private fun fn(name: String, description: String, params: JSONObject, required: List<String>): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put(
                "parameters",
                JSONObject().put("type", "object").put("properties", params).put("required", JSONArray(required)),
            )

    private fun prop(type: String, description: String? = null, enum: List<String>? = null): JSONObject =
        JSONObject().put("type", type)
            .apply { if (description != null) put("description", description) }
            .apply { if (enum != null) put("enum", JSONArray(enum)) }

    /** Null when [name] is not this tool, so [LiveToolbox.dispatch] can fall through. */
    suspend fun dispatch(context: Context, name: String, args: JSONObject): JSONObject? {
        if (name != TOOL_NAME) return null
        val c = controllerFactory(context)
        val taskId = args.optInt("task_id", 0)
        return when (args.optString("action")) {
            "send" -> sendResult(
                c.send(
                    request = args.optString("request"),
                    machine = args.optString("machine").ifBlank { null },
                    origin = if (args.optBoolean("offered", false)) {
                        ClaudeTaskDraft.ORIGIN_OFFERED
                    } else {
                        ClaudeTaskDraft.ORIGIN_NAMED
                    },
                    emailMe = args.optBoolean("email_me", false),
                ),
            )
            "check" -> check(c, args.optString("machine").ifBlank { null })
            "answer" -> needsTask(taskId) ?: sendResult(
                c.answer(taskId, args.optString("request"), args.optBoolean("yes", false), args.optBoolean("email_me")),
            )
            "assign" -> needsTask(taskId) ?: sendResult(c.assign(taskId, args.optString("machine")))
            "send_file" -> needsTask(taskId) ?: sendResult(c.sendFile(taskId, args.optInt("file_id", 0)))
            "quiet" -> {
                watchFactory(context).setAnnouncements(false)
                result(true, "Result announcements are off. Results still wait on the Claude session screen.")
            }
            "unquiet" -> {
                watchFactory(context).setAnnouncements(true)
                result(true, "Result announcements are on again.")
            }
            else -> result(false, "Say whether to send, check, answer, assign or send a file. Nothing was sent.")
        }
    }

    private fun needsTask(taskId: Int): JSONObject? =
        if (taskId > 0) null else result(false, "Which task? Nothing was sent.")

    private fun sendResult(outcome: ClaudeSendResult): JSONObject = when (outcome) {
        is ClaudeSendResult.Sent -> result(true, outcome.sentence).put("task_id", outcome.created.id)
        is ClaudeSendResult.NeedsMachine -> result(false, outcome.sentence)
            .put("needs_machine", true)
            .apply { outcome.taskId?.let { put("task_id", it) } }
        is ClaudeSendResult.NotSent -> result(false, outcome.sentence)
    }

    /**
     * Machines with their state, then the newest tasks with their status, summary, question, live
     * step and files (ids included, for `send_file`), then the interactive sessions, waiting first.
     */
    @Suppress("ReturnCount") // unreadable, no machine ever, or the answer
    private suspend fun check(c: ClaudeSessionController, machine: String?): JSONObject {
        val overview = c.overview()
        if (overview !is ClaudeOutcome.Ok) {
            return result(false, ClaudeWording.failed(overview, "read the Claude session"))
        }
        val o = overview.value
        val wanted = { m: String? -> machine == null || m?.contains(machine.trim(), ignoreCase = true) == true }
        val machines = o.status.machines.filter { wanted(it.name) }
        if (o.status.machines.isEmpty()) return result(true, ClaudeWording.NO_MACHINE_EVER.substringBefore(","))
        val lines = mutableListOf<String>()
        machines.forEach { lines += c.wording.machineLine(it) }
        val tasks = o.tasks.filter { wanted(it.machine) }.take(CHECK_TASKS)
        if (tasks.isEmpty()) lines += "No tasks yet."
        tasks.forEach { t ->
            val files = if (o.summariesOnly(t)) "" else t.files.joinToString("") { f ->
                " [file_id ${f.id}: ${f.name}, ${ClaudeWording.size(f.size)}" +
                    (if (f.uploaded) ", already on the engine" else "") + "]"
            }
            lines += c.wording.result(t, summariesOnly = true) + files
        }
        o.status.sessions.filter { wanted(it.machine) }.forEach { s ->
            val doing = s.lastPromptSummary?.let { ": $it" } ?: ""
            lines += "Session on the ${s.machine}, ${s.project.ifBlank { "no project" }}, " +
                (if (s.waitingOnYou) "waiting on you" else s.state) + doing
        }
        return result(true, lines.joinToString("\n"))
    }

    private fun result(success: Boolean, message: String): JSONObject =
        JSONObject().put("success", success).put("message", message)

    private const val CHECK_TASKS = 5
}
