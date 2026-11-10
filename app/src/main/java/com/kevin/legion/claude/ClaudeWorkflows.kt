package com.kevin.legion.claude

import java.time.Instant

/** A workflow as `GET status/` lists it per machine: the name (`log-audit`) and its one line. */
data class ClaudeWorkflowName(val name: String, val says: String)

/** One parameter a workflow takes, as `GET workflows/` lists it. */
data class ClaudeWorkflowParam(val name: String, val says: String, val required: Boolean)

/** A workflow's schedule (catch-up, worker-workflows ticket 04): due every [hours] when idle. */
data class ClaudeWorkflowEvery(val hours: Int, val idleMinutes: Int?, val forceAfterHours: Int?)

/** The newest run of a workflow: its task, status, who started it and when. */
data class ClaudeWorkflowRun(
    val task: Int,
    val status: String,
    val origin: String,
    val createdAt: Instant?,
    val finishedAt: Instant?,
)

/**
 * One row of `GET workflows/` (worker-workflows ticket 03, map "Phone contract"). The engine's copy
 * is derived from the laptop's `workflow.json` (ruling R1): names, one-liners, param names, the
 * questions it stops at ([asks]) and the schedule, never a skill's body or a path.
 */
data class ClaudeWorkflow(
    val machine: String,
    val machineState: String,
    val name: String,
    /** "log audit": the name as Alfred says it. */
    val spoken: String,
    val says: String,
    val params: List<ClaudeWorkflowParam>,
    /** What it stops for Kevin's yes at, in words ("Delete the older reports?"). */
    val asks: List<String>,
    /** `email_self` when every run mails its result to Kevin (R3's one standing yes), else null. */
    val deliver: String?,
    val every: ClaudeWorkflowEvery?,
    val lastRun: ClaudeWorkflowRun?,
    /** A run is queued, running or waiting on his yes: Run would be refused (409). */
    val waiting: Boolean,
    /** Why its schedule does not run on this machine, in words (the work laptop), else null. */
    val scheduleRefused: String?,
    val due: Boolean,
)

/** The words for workflows, shared by the voice tool, the screen and the scheduled-question raise. */
object ClaudeWorkflowWording {

    /** A workflow asked of an engine from before workflows (claude_session 0007). Nothing is queued. */
    const val NOT_ON_ENGINE =
        "Nothing was sent. Workflows are not available yet: the engine has not been updated for them. " +
            "Say it as an ordinary request if it should go to Claude anyway."

    /** "log-audit" as Alfred says it, matching the engine's `spoken`. */
    fun spoken(name: String): String = name.replace("-", " ")

    /** `check`'s line for one machine: names only (map, "Phone contract"). Null when it has none. */
    fun checkLine(machine: ClaudeMachine): String? {
        if (machine.workflows.isEmpty()) return null
        return "Workflows on the ${machine.name}: " + machine.workflows.joinToString { spoken(it.name) } + "."
    }

    /**
     * "what does log audit do?" answered from the same list: the one-liner of each workflow whose
     * spoken name appears in [question]. Empty when none does.
     */
    fun describe(question: String, machines: List<ClaudeMachine>): List<String> {
        val q = " " + question.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(" ")
            .filter { it.isNotBlank() }.joinToString(" ") + " "
        return machines.flatMap { it.workflows }.distinctBy { it.name }
            .filter { q.contains(" " + spoken(it.name).lowercase() + " ") }
            .map { "${spoken(it.name).replaceFirstChar(Char::uppercase)}: ${it.says}" }
    }

    /** "It stops for your yes before: ..." from the run's declared asks, or "" with none. */
    fun stops(asks: List<String>): String =
        if (asks.isEmpty()) "" else " It stops for your yes first: " + asks.joinToString(" ") { it.trim() }

    /** The sentence for a workflow run the engine accepted. Never "done": the result says that. */
    fun sent(created: ClaudeCreated, wording: ClaudeWording): String {
        val name = created.workflow?.let(::spoken) ?: "the workflow"
        val m = created.machine
        val where = when {
            m == null -> "Queued $name for whichever machine has it and comes on first."
            m.state != ClaudeMachine.STATE_OFFLINE ->
                "Sent to the ${m.name}: $name. It is not done yet; I'll say when there is a result."
            else -> "The ${m.name} is ${wording.machineState(m.state, m.lastSeenAt)}, so $name is queued and " +
                "runs when it is back on. Nothing has run yet."
        }
        return where + stops(created.asks)
    }

    /** The schedule in words for the screen: "Runs on its own about every day when the Alienware is idle." */
    @Suppress("ReturnCount") // no schedule, refused on this machine, or the schedule
    fun schedule(w: ClaudeWorkflow): String? {
        val every = w.every ?: return null
        w.scheduleRefused?.let { return it }
        val span = when {
            every.hours % HOURS_PER_WEEK == 0 -> plural(every.hours / HOURS_PER_WEEK, "week")
            every.hours % HOURS_PER_DAY == 0 -> plural(every.hours / HOURS_PER_DAY, "day")
            else -> plural(every.hours, "hour")
        }
        return "Runs on its own about every $span when the ${w.machine} is idle, and stops at every question."
    }

    /** The last run in words: "Last run: done, task 12." or "Never run." */
    fun lastRun(w: ClaudeWorkflow, wording: ClaudeWording): String {
        val run = w.lastRun ?: return "Never run."
        val who = if (run.origin == ClaudeTaskDraft.ORIGIN_SCHEDULE) " (scheduled)" else ""
        val status = statusWords(run.status)
        val time = (run.finishedAt ?: run.createdAt)?.let { ", ${wording.clock(it)}" } ?: ""
        return "Last run$who: $status$time, task ${run.task}."
    }

    /** The mail a workflow sends Kevin on every run (R3), said wherever it is offered. */
    fun deliverLine(w: ClaudeWorkflow): String? =
        if (w.deliver == ClaudeTaskDraft.DELIVER_EMAIL_SELF) "Every run emails its result to you." else null

    /** The raise for a scheduled run that stopped for his yes (ruling R3). */
    fun scheduledQuestion(task: ClaudeTask): String {
        val head = task.workflow?.let { "Scheduled ${spoken(it)}" } ?: "A scheduled run"
        val on = task.machine?.let { " on the $it" } ?: ""
        val q = task.question?.trim().orEmpty().ifEmpty { "It is waiting for your yes." }
        return "$head$on (task ${task.id}) stopped to ask: $q"
    }

    private fun statusWords(status: String): String = when (status) {
        ClaudeTask.NEEDS_CONFIRMATION -> "waiting for your yes"
        ClaudeTask.NEEDS_MACHINE -> "waiting for a machine"
        ClaudeTask.CANCEL_REQUESTED -> "stopping"
        else -> status
    }

    private fun plural(n: Int, unit: String) = if (n == 1) unit else "$n ${unit}s"

    private const val HOURS_PER_DAY = 24
    private const val HOURS_PER_WEEK = 168
}
