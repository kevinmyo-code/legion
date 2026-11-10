package com.kevin.legion.claude

import android.content.Context
import com.kevin.legion.backend.engine.DjangoClaudeSessionBackend
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp

/** What a send, an assign or a follow-up came to. [sentence] is always set and always honest. */
sealed interface ClaudeSendResult {
    val sentence: String

    /** A 2xx: the task exists on the engine, `queued` for a machine. Never "done". */
    data class Sent(val created: ClaudeCreated, override val sentence: String) : ClaudeSendResult

    /**
     * Not sent to any machine yet: Kevin must pick one (rulings 4, Kevin 2026-11-01). [taskId] is
     * set when the engine already holds the task as `needs_machine` (answer through [assign]);
     * null when nothing was created (answer by sending again with the machine named).
     */
    data class NeedsMachine(val taskId: Int?, val choices: List<String>, override val sentence: String) :
        ClaudeSendResult

    /** Nothing was sent, or (an unreadable 2xx) it is not known; [sentence] says which. */
    data class NotSent(override val sentence: String) : ClaudeSendResult
}

/** The machines, the newest tasks and the interactive sessions, read together for check and the screen. */
data class ClaudeOverview(val status: ClaudeStatus, val tasks: List<ClaudeTask>) {
    fun machineFor(task: ClaudeTask): ClaudeMachine? =
        status.machines.firstOrNull { it.name.equals(task.machine, ignoreCase = true) }

    fun summariesOnly(task: ClaudeTask): Boolean = machineFor(task)?.summariesOnly == true
}

/**
 * The one controller the `claude_session` voice tool, the result watcher and the Claude session
 * screen all call (ADR 0035: two doors onto one implementation). Online only: there is no Room
 * table; every call goes to the engine (`/api/claude/`, ADR 0067), and every result says in words
 * what did and did not happen.
 *
 * **The rules it owns, so neither door can skip them:**
 * - machine choice (ruling 4, Kevin 2026-11-01): a named machine wins; unnamed goes to the only
 *   machine online; more than one online is a "which one?" answered through `assign/`. With none
 *   online it queues for the only machine there is, or asks which when there are several.
 * - `deliver: email_self` only when Kevin named his own email ([send]'s `emailMe`, ruling 17).
 * - `deliver: phone` only when he asked for the result as a page ([send]'s `page`, ticket 09), and
 *   never to a summaries-only machine (the engine refuses it too).
 * - `deliver: files` only when he asked for files on his phone ([send]'s `files`, ADR 0080); the
 *   same refusal for a summaries-only machine.
 * - `confirms: true` only on Kevin's yes to a task that is `needs_confirmation` ([answer]).
 * - no file from a `listed` machine ever reaches the phone ([sendFile], ruling 5).
 *
 * [onSent] is told each task id the engine accepted, so the result watcher can announce it.
 */
// ReturnCount: each rule is a guard clause that returns the sentence saying what did not happen.
// TooManyFunctions: one function per thing either door can do (ADR 0035).
@Suppress("ReturnCount", "TooManyFunctions")
class ClaudeSessionController(
    private val backend: ClaudeSessionBackend,
    val wording: ClaudeWording = ClaudeWording(),
    private val onSent: (Int) -> Unit = {},
    private val onFileRequested: (taskId: Int, fileId: Int, name: String) -> Unit = { _, _, _ -> },
) {

    suspend fun status(): ClaudeOutcome<ClaudeStatus> = backend.status()

    suspend fun task(id: Int): ClaudeOutcome<ClaudeTask> = backend.task(id)

    /** Status and the newest tasks, or the first failure. */
    suspend fun overview(limit: Int = RECENT): ClaudeOutcome<ClaudeOverview> {
        val status = backend.status()
        if (status !is ClaudeOutcome.Ok) return status.asFailure()
        val tasks = backend.tasks(limit)
        if (tasks !is ClaudeOutcome.Ok) return tasks.asFailure()
        return ClaudeOutcome.Ok(ClaudeOverview(status.value, tasks.value))
    }

    /**
     * Sends a new task. [machine] is the spoken name or null; [origin] is `named` (Kevin asked for
     * the Claude session) or `offered` (his yes to the offer). [emailMe] is true only when he asked
     * for it to be emailed to himself; [page] only when he wants the result as a page on his phone.
     */
    @Suppress("CyclomaticComplexMethod") // each send rule is one guard; split, they would hide the order
    suspend fun send(
        request: String,
        machine: String?,
        origin: String,
        emailMe: Boolean,
        page: Boolean = false,
        files: Boolean = false,
    ): ClaudeSendResult {
        val text = request.trim()
        if (text.isEmpty()) return ClaudeSendResult.NotSent(ClaudeWording.EMPTY_REQUEST)
        val status = backend.status()
        if (status !is ClaudeOutcome.Ok) return ClaudeSendResult.NotSent(ClaudeWording.failed(status, "send it"))
        val machines = status.value.machines
        if (machines.isEmpty()) return ClaudeSendResult.NotSent(ClaudeWording.NO_MACHINE_EVER)

        val target: String? = if (!machine.isNullOrBlank()) {
            ClaudeMachines.resolve(machine, machines.map { it.name })
                ?: return ClaudeSendResult.NotSent(wording.unknownMachine(machine.trim(), machines.map { it.name }))
        } else {
            val online = machines.filter { it.isOnline }
            when {
                online.size == 1 -> online.single().name
                online.size > 1 -> null // the engine holds it as needs_machine; Kevin answers via assign
                machines.size == 1 -> machines.single().name
                else -> {
                    val names = machines.map { it.name }
                    return ClaudeSendResult.NeedsMachine(null, names, wording.whichMachine(names, created = false))
                }
            }
        }
        val summariesOnly = machines.firstOrNull { it.name == target }?.summariesOnly == true
        if (page && summariesOnly) {
            return ClaudeSendResult.NotSent(wording.pageNeedsFullMachine(target.orEmpty()))
        }
        if (files && summariesOnly) {
            return ClaudeSendResult.NotSent(wording.filesNeedFullMachine(target.orEmpty()))
        }
        val draft = ClaudeTaskDraft(
            request = text,
            machine = target,
            parent = null,
            origin = origin,
            deliver = when {
                emailMe -> ClaudeTaskDraft.DELIVER_EMAIL_SELF
                page -> ClaudeTaskDraft.DELIVER_PHONE
                files -> ClaudeTaskDraft.DELIVER_FILES
                else -> null
            },
            confirms = false,
        )
        val online = machines.filter { it.isOnline }.map { it.name }
        return created(backend.create(draft), choices = online, followUp = false)
    }

    /**
     * Starts a workflow (worker-workflows ticket 06). Both doors call this (ADR 0035):
     * - voice (`action: "workflow"`): [phrase] is Kevin's words; the engine resolves them against the
     *   machines' catalogs (`POST tasks/ {workflow}`) and the words after the name become its params.
     * - the screen's Run ([byName]): [phrase] is the exact name, [params] the typed text
     *   (`POST workflows/run/`).
     *
     * [machine] is sent ONLY when Kevin named one: the engine reads any machine as named, so picking
     * the one that is on (as [send] does) would refuse a workflow that lives on a machine that is off
     * instead of queueing it there. A miss (400) reads the engine's sentence, which names the
     * workflows there are; a run already going (409) says so. Never "done": the result says that.
     */
    @Suppress("CyclomaticComplexMethod") // each outcome is one sentence
    suspend fun runWorkflow(
        phrase: String,
        machine: String?,
        params: String? = null,
        byName: Boolean = false,
    ): ClaudeSendResult {
        val text = phrase.trim()
        if (text.isEmpty()) return ClaudeSendResult.NotSent(ClaudeWording.EMPTY_REQUEST)
        val status = backend.status()
        if (status !is ClaudeOutcome.Ok) return ClaudeSendResult.NotSent(ClaudeWording.failed(status, "start it"))
        val machines = status.value.machines
        if (machines.isEmpty()) return ClaudeSendResult.NotSent(ClaudeWording.NO_MACHINE_EVER)
        // An old engine drops the `workflow` key and queues the phrase as an ordinary task.
        if (!status.value.workflowsKnown) return ClaudeSendResult.NotSent(ClaudeWorkflowWording.NOT_ON_ENGINE)
        val target = if (machine.isNullOrBlank()) {
            null
        } else {
            ClaudeMachines.resolve(machine, machines.map { it.name })
                ?: return ClaudeSendResult.NotSent(wording.unknownMachine(machine.trim(), machines.map { it.name }))
        }
        val out = if (byName) {
            backend.runWorkflow(target, text, params?.trim()?.ifEmpty { null })
        } else {
            backend.create(
                ClaudeTaskDraft(
                    request = text,
                    machine = target,
                    parent = null,
                    origin = ClaudeTaskDraft.ORIGIN_NAMED,
                    deliver = null, // the engine takes the workflow's own (its standing mail, or its page)
                    confirms = false,
                    workflow = text,
                ),
            )
        }
        return when (out) {
            is ClaudeOutcome.Ok -> {
                onSent(out.value.id)
                if (out.value.status == ClaudeTask.NEEDS_MACHINE) {
                    val online = machines.filter { it.isOnline }.map { it.name }
                    ClaudeSendResult.NeedsMachine(out.value.id, online, wording.whichMachine(online, created = true))
                } else {
                    ClaudeSendResult.Sent(out.value, ClaudeWorkflowWording.sent(out.value, wording))
                }
            }
            // The engine's 400 and 409 are already whole sentences ("Nothing was sent. ...").
            is ClaudeOutcome.Refused -> ClaudeSendResult.NotSent(
                if (out.sentence == ClaudeWording.NOT_ON_ENGINE) ClaudeWorkflowWording.NOT_ON_ENGINE else out.sentence,
            )
            else -> ClaudeSendResult.NotSent(ClaudeWording.failed(out, "start it"))
        }
    }

    /** Every machine's workflows with their last run, for the screen (`GET workflows/`). */
    suspend fun workflows(): ClaudeOutcome<List<ClaudeWorkflow>> = backend.workflows()

    /** Scheduled runs that stopped for Kevin's yes (ruling R3), newest first. */
    suspend fun scheduledQuestions(limit: Int = SCHEDULED_QUESTIONS): ClaudeOutcome<List<ClaudeTask>> =
        backend.tasksWhere(ClaudeTaskDraft.ORIGIN_SCHEDULE, ClaudeTask.NEEDS_CONFIRMATION, limit)

    /** Kevin answered "which machine?" for a `needs_machine` task. */
    suspend fun assign(taskId: Int, machine: String): ClaudeSendResult {
        val status = backend.status()
        val names = (status as? ClaudeOutcome.Ok)?.value?.machines?.map { it.name }.orEmpty()
        val resolved = ClaudeMachines.resolve(machine, names) ?: machine.trim()
        return created(backend.assign(taskId, resolved), choices = names, followUp = false)
    }

    /**
     * A follow-up to task [taskId] (ruling 10): an answer to its question, or "now do X with it".
     * [yes] is Kevin's yes to the task's question; it becomes `confirms: true` ONLY when the task is
     * `needs_confirmation`, the one thing that unlocks the asked ask-list step on the worker. Every
     * other follow-up carries no `confirms`.
     */
    suspend fun answer(taskId: Int, answer: String, yes: Boolean, emailMe: Boolean): ClaudeSendResult {
        val text = answer.trim().ifEmpty { if (yes) "Yes, go ahead." else "" }
        if (text.isEmpty()) return ClaudeSendResult.NotSent(ClaudeWording.EMPTY_REQUEST)
        val parent = backend.task(taskId)
        if (parent !is ClaudeOutcome.Ok) {
            return ClaudeSendResult.NotSent(ClaudeWording.failed(parent, "send the follow-up to task $taskId"))
        }
        val draft = ClaudeTaskDraft(
            request = text,
            machine = null, // the engine sends a follow-up to its parent's machine
            parent = taskId,
            origin = ClaudeTaskDraft.ORIGIN_NAMED,
            deliver = if (emailMe) ClaudeTaskDraft.DELIVER_EMAIL_SELF else null,
            confirms = yes && parent.value.needsConfirmation,
        )
        return created(backend.create(draft), choices = emptyList(), followUp = true)
    }

    /** Kevin said yes to "want it on your phone?". Refused for a summaries-only machine. */
    suspend fun sendFile(taskId: Int, fileId: Int): ClaudeSendResult {
        val overview = overviewFor(taskId)
        if (overview !is ClaudeOutcome.Ok) {
            return ClaudeSendResult.NotSent(ClaudeWording.failed(overview, "ask for that file"))
        }
        val (task, machine) = overview.value
        if (machine?.summariesOnly == true) return ClaudeSendResult.NotSent(wording.summariesOnly(machine.name))
        val file = task.files.firstOrNull { it.id == fileId }
            ?: return ClaudeSendResult.NotSent("Task $taskId has no file $fileId. Nothing was requested.")
        return when (val out = backend.sendFile(taskId, fileId)) {
            is ClaudeOutcome.Ok -> {
                onFileRequested(taskId, fileId, file.name)
                ClaudeSendResult.Sent(
                    ClaudeCreated(taskId, task.status, null),
                    wording.fileRequested(file, task.machine),
                )
            }
            else -> ClaudeSendResult.NotSent(ClaudeWording.failed(out, "ask for ${file.name}"))
        }
    }

    suspend fun download(taskId: Int, fileId: Int): ClaudeOutcome<ByteArray> = backend.download(taskId, fileId)

    /** This phone has delivered file [fileId] of task [taskId]; the engine deletes its copy (ADR 0080). */
    suspend fun received(taskId: Int, fileId: Int): ClaudeOutcome<Unit> = backend.received(taskId, fileId)

    private suspend fun overviewFor(taskId: Int): ClaudeOutcome<Pair<ClaudeTask, ClaudeMachine?>> {
        val task = backend.task(taskId)
        if (task !is ClaudeOutcome.Ok) return task.asFailure()
        val status = backend.status()
        if (status !is ClaudeOutcome.Ok) return status.asFailure()
        val machine = status.value.machines.firstOrNull { it.name.equals(task.value.machine, ignoreCase = true) }
        return ClaudeOutcome.Ok(task.value to machine)
    }

    private fun created(
        out: ClaudeOutcome<ClaudeCreated>,
        choices: List<String>,
        followUp: Boolean,
    ): ClaudeSendResult =
        when (out) {
            is ClaudeOutcome.Ok -> {
                onSent(out.value.id)
                if (out.value.status == ClaudeTask.NEEDS_MACHINE) {
                    ClaudeSendResult.NeedsMachine(out.value.id, choices, wording.whichMachine(choices, created = true))
                } else {
                    ClaudeSendResult.Sent(out.value, wording.sent(out.value, followUp))
                }
            }
            else -> ClaudeSendResult.NotSent(
                ClaudeWording.failed(out, if (followUp) "send the follow-up" else "send it"),
            )
        }

    companion object {
        const val RECENT = 20
        const val SCHEDULED_QUESTIONS = 20

        /** The production wiring: the engine over the saved address and token, results watched. */
        fun forContext(context: Context): ClaudeSessionController {
            val app = context.applicationContext
            val watch = ClaudeWatchStore(app)
            return ClaudeSessionController(
                backend = DjangoClaudeSessionBackend(EngineHttp(EngineConfig(app))),
                onSent = watch::watchTask,
                onFileRequested = watch::watchFile,
            )
        }
    }
}

/** Spoken machine names (ruling 4): "my Alienware" and "the work laptop" find the engine's rows. */
@Suppress("ReturnCount")
object ClaudeMachines {
    const val ALIENWARE = "Alienware"
    const val WORK_LAPTOP = "work laptop"

    /** The engine's own name for what Kevin said, or null when no known machine matches. */
    fun resolve(spoken: String, known: List<String>): String? {
        val said = spoken.trim().lowercase().removePrefix("my ").removePrefix("the ").trim()
        known.firstOrNull { it.equals(said, ignoreCase = true) }?.let { return it }
        val canonical = when {
            "alienware" in said || "gaming" in said -> ALIENWARE
            "work" in said -> WORK_LAPTOP
            else -> return null
        }
        return known.firstOrNull { it.equals(canonical, ignoreCase = true) }
    }
}
