package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.claude.ClaudeMachine
import com.kevin.legion.claude.ClaudeModalHolder
import com.kevin.legion.claude.ClaudeOutcome
import com.kevin.legion.claude.ClaudePageOpener
import com.kevin.legion.claude.ClaudeSendResult
import com.kevin.legion.claude.ClaudeSessionController
import com.kevin.legion.claude.ClaudeTask
import com.kevin.legion.claude.ClaudeTaskDraft
import com.kevin.legion.claude.ClaudeWatch
import com.kevin.legion.claude.ClaudeWatchStore
import com.kevin.legion.claude.ClaudeWorkflowWording
import com.kevin.legion.claude.ClaudeWording
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Claude session's voice surface (claude-session ticket 05, ADR 0067) as ONE `claude_session`
 * tool with an `action`, not four: [LiveSetupPayloadSizeTest] holds the Live setup message under a
 * ceiling with almost no headroom, the trade `bought_log` and `books` made. The ticket's four tools
 * are actions here (`send`, `check`, `answer`, `send_file`), plus `assign` (the answer to "which
 * machine?", Kevin 2026-11-01), `quiet` (the one-instruction silence of the result
 * announcements) and `open_page` (ticket 09: a `page: true` task's web page, opened in LEGION's
 * browser through [ClaudePageOpener], on his yes). `files: true` on `send` (ADR 0080) asks for the
 * files he named on his phone; they open there when they land, no second yes.
 * `tools/voice_guide.py` and `VoiceGuideDataTest` scan this file.
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

    /** Test seam: the process-wide holder the task dialog over the app follows (Kevin 2026-11-04). */
    internal var modalFactory: (Context) -> ClaudeModalHolder = ClaudeModalHolder::get

    private const val DESCRIPTION =
        "Kevin's Claude session on his laptops (Alienware, work laptop). send queues a task; workflow runs " +
            "a named one (check lists them); check only " +
            "reads results already in; \"ask Claude to...\" is always a new send or workflow; answer follows up a " +
            "task (yes=true only for his yes to its question); assign picks the machine it asked for; " +
            "send_file only on his yes to a found file; open_page opens a task's page on his yes; " +
            "quiet/unquiet stop/restart result announcements. A sent task is not done."

    fun declarations(): JSONArray = JSONArray().put(
        fn(
            name = "claude_session",
            description = DESCRIPTION,
            params = JSONObject()
                .put(
                    "action",
                    prop(
                        "string",
                        null,
                        listOf(
                            "send", "workflow", "check", "answer", "assign", "send_file", "open_page", "quiet",
                            "unquiet",
                        ),
                    ),
                )
                .put("request", prop("string", "The task, or the follow-up, in his words."))
                .put("machine", prop("string", "Only if named."))
                .put("offered", prop("boolean", "True if this is his yes to your offer."))
                .put("email_me", prop("boolean", "True only if he asked to email it to himself."))
                .put("page", prop("boolean", "True for a researched write-up he reads on his phone (Alienware)."))
                .put("files", prop("boolean", "True if he asked for files on his phone."))
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

    /**
     * Null when [name] is not this tool, so [LiveToolbox.dispatch] can fall through.
     *
     * [touchedReadThroughToolThisTurn] is the turn's read-through flag (mail, web, GitHub text, a
     * camera look, the turn after a spoken brief or mail line), the one [WatchesToolbox] and
     * `remember` check through [LiveToolbox.rememberBlockedByReadThroughTool]. Starting a workflow is
     * a durable outward action (it runs Claude on the laptop, may mail Kevin and may come back on a
     * schedule's questions), so text other people wrote must never be able to start one: `workflow`
     * is refused in such a turn before anything is sent.
     */
    // One branch per action, each a one-line call to the controller; the returns are "not this
    // tool", the read-through refusal, and the action's result.
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    suspend fun dispatch(
        context: Context,
        name: String,
        args: JSONObject,
        touchedReadThroughToolThisTurn: Boolean = false,
    ): JSONObject? {
        if (name != TOOL_NAME) return null
        val action = args.optString("action")
        val readThrough = LiveToolbox.rememberBlockedByReadThroughTool(touchedReadThroughToolThisTurn)
        if (action in READ_THROUGH_GUARDED && readThrough) return result(false, READ_THROUGH_REFUSAL)
        val c = controllerFactory(context)
        val taskId = args.optInt("task_id", 0)
        val shown = { out: ClaudeSendResult -> sendResult(showDialog(context, out)) }
        return when (action) {
            "workflow" -> shown(
                c.runWorkflow(phrase = args.optString("request"), machine = args.optString("machine").ifBlank { null }),
            )
            "send" -> shown(
                c.send(
                    request = args.optString("request"),
                    machine = args.optString("machine").ifBlank { null },
                    origin = if (args.optBoolean("offered", false)) {
                        ClaudeTaskDraft.ORIGIN_OFFERED
                    } else {
                        ClaudeTaskDraft.ORIGIN_NAMED
                    },
                    emailMe = args.optBoolean("email_me", false),
                    page = args.optBoolean("page", false),
                    files = args.optBoolean("files", false),
                ),
            )
            "check" -> check(
                c,
                args.optString("machine").ifBlank { null },
                watchFactory(context),
                question = args.optString("request"),
            )
            "answer" -> needsTask(taskId) ?: shown(
                c.answer(taskId, args.optString("request"), args.optBoolean("yes", false), args.optBoolean("email_me")),
            )
            "assign" -> needsTask(taskId) ?: shown(c.assign(taskId, args.optString("machine")))
            "send_file" -> needsTask(taskId) ?: sendResult(c.sendFile(taskId, args.optInt("file_id", 0)))
            "open_page" -> needsTask(taskId) ?: ClaudePageOpener.open(context, c, taskId).let {
                result(it.opened, it.sentence)
            }
            "quiet" -> {
                watchFactory(context).setAnnouncements(false)
                result(true, "Result announcements are off. Results still wait on the Claude session screen.")
            }
            "unquiet" -> {
                watchFactory(context).setAnnouncements(true)
                result(true, "Result announcements are on again.")
            }
            else -> result(
                false,
                "Say whether to send, run a workflow, check, answer, assign or send a file. Nothing was sent.",
            )
        }
    }

    /** The actions a read-through turn may not take: each starts Claude work on a laptop. */
    private val READ_THROUGH_GUARDED = setOf("workflow")

    internal const val READ_THROUGH_REFUSAL =
        "Nothing was started. I don't start a Claude workflow in the same breath as reading mail, a web page, " +
            "the camera or a spoken brief, on purpose. Ask me again in your own words."

    /**
     * A task the engine now holds (sent, queued, or waiting for a machine) opens the task dialog
     * over the app. Nothing that was not created opens anything.
     */
    private fun showDialog(context: Context, out: ClaudeSendResult): ClaudeSendResult {
        val id = when (out) {
            is ClaudeSendResult.Sent -> out.created.id
            is ClaudeSendResult.NeedsMachine -> out.taskId
            is ClaudeSendResult.NotSent -> null
        }
        if (id != null && id > 0) modalFactory(context).show(id)
        return out
    }

    private fun needsTask(taskId: Int): JSONObject? =
        if (taskId > 0) null else result(false, "Which task? Nothing was sent.")

    private fun sendResult(outcome: ClaudeSendResult): JSONObject = when (outcome) {
        // `done: false` (phone audit 2026-11-10): `success` means the engine took the request, never
        // that the task's own work (an email, a file) happened; the model read it as the latter.
        is ClaudeSendResult.Sent -> result(true, outcome.sentence).put("task_id", outcome.created.id).put("done", false)
        is ClaudeSendResult.NeedsMachine -> result(false, outcome.sentence)
            .put("needs_machine", true)
            .apply { outcome.taskId?.let { put("task_id", it) } }
        is ClaudeSendResult.NotSent -> result(false, outcome.sentence)
    }

    /**
     * Machines with their state, then the newest tasks with their status, summary, question, live
     * step and files (ids included, for `send_file`), then the interactive sessions, waiting first.
     *
     * **A finished result this reads out is announced** ([consumeAnnouncement]), so the result
     * watcher does not say it again (2026-11-05, A25 LIVE_AUDIT 11-04: asked "what did Claude say",
     * the check spoke task 6's result and the watcher's queued note spoke it a second time).
     */
    @Suppress("ReturnCount") // unreadable, no machine ever, or the answer
    private suspend fun check(
        c: ClaudeSessionController,
        machine: String?,
        watch: ClaudeWatch,
        question: String = "",
    ): JSONObject {
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
        lines += workflowLines(machines, question)
        val tasks = o.tasks.filter { wanted(it.machine) }.take(CHECK_TASKS)
        if (tasks.isEmpty()) lines += "No tasks yet."
        tasks.forEach { t ->
            // A delivered page is opened (open_page), never offered as a file (ticket 09); files asked
            // for by name are already sent (ADR 0080), and the result line says so.
            val offered = t.offeredFiles
            val files = if (o.summariesOnly(t)) "" else offered.joinToString("") { f ->
                " [file_id ${f.id}: ${f.name}, ${ClaudeWording.size(f.size)}" +
                    (if (f.uploaded) ", already on the engine" else "") + "]"
            }
            lines += c.wording.result(t, summariesOnly = true) + files
            consumeAnnouncement(t, watch)
        }
        o.status.sessions.filter { wanted(it.machine) }.forEach { s ->
            val doing = s.lastPromptSummary?.let { ": $it" } ?: ""
            lines += "Session on the ${s.machine}, ${s.project.ifBlank { "no project" }}, " +
                (if (s.waitingOnYou) "waiting on you" else s.state) + doing
        }
        return result(true, lines.joinToString("\n"))
    }

    /**
     * Worker workflows (ticket 06): `check`'s names per online machine, then "what does log audit
     * do?" answered from the same list (the one-liner of each workflow [question] names).
     */
    private fun workflowLines(machines: List<ClaudeMachine>, question: String): List<String> =
        machines.filter { it.isOnline }.mapNotNull(ClaudeWorkflowWording::checkLine) +
            ClaudeWorkflowWording.describe(question, machines)

    /**
     * A finished task whose result [check] just handed the model: forgotten by the watch, and its
     * note withdrawn if one is waiting in the conversation. A task still waiting on its page is
     * left alone, because the watcher's announcement is what will say the page is ready.
     */
    internal fun consumeAnnouncement(task: ClaudeTask, watch: ClaudeWatch) {
        if (!task.isFinished || task.pagePending || task.filesPending) return
        if (task.id in watch.watchedTasks()) watch.forgetTask(task.id)
        LiveNotes.withdraw(LiveNotes.claudeTaskKey(task.id))
    }

    private fun result(success: Boolean, message: String): JSONObject =
        JSONObject().put("success", success).put("message", message)

    private const val CHECK_TASKS = 5
}
