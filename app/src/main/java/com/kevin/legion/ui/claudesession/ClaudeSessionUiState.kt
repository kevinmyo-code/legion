package com.kevin.legion.ui.claudesession

import com.kevin.legion.claude.ClaudeCodeSession
import com.kevin.legion.claude.ClaudeMachine
import com.kevin.legion.claude.ClaudeOverview
import com.kevin.legion.claude.ClaudeStep
import com.kevin.legion.claude.ClaudeSteps
import com.kevin.legion.claude.ClaudeTask
import com.kevin.legion.claude.ClaudeWording

data class MachineRowUi(val name: String, val stateText: String, val online: Boolean, val summariesOnly: Boolean)

/** One kept step under a task: "3:40 PM" and "reading Downloads". */
data class StepRowUi(val time: String, val text: String)

data class FileRowUi(val id: Int, val name: String, val sizeText: String, val uploaded: Boolean, val requested: Boolean)

data class TaskRowUi(
    val id: Int,
    val request: String,
    val machine: String?,
    val statusText: String,
    val summary: String?,
    val question: String?,
    /** The helper's live step while it runs ("searching Downloads"), else null. */
    val progress: String?,
    val files: List<FileRowUi>,
    /** False for a summaries-only machine (the work laptop, ruling 5): no Send to phone. */
    val filesMayCome: Boolean,
    val needsConfirmation: Boolean,
    val needsMachine: Boolean,
    /** A delivered page's title once it is on the engine (ticket 09): an Open page button. */
    val pageTitle: String? = null,
    /** Asked for as a page, and the page has not arrived (yet). */
    val pageWaiting: Boolean = false,
    /** The task's kept steps, newest first, shown under its detail (finished tasks keep theirs). */
    val steps: List<StepRowUi> = emptyList(),
)

data class SessionRowUi(
    val machine: String,
    val project: String,
    val stateText: String,
    val waiting: Boolean,
    val summary: String?,
)

/**
 * The Claude session screen's one state. [unavailable] is "could not read", said in words, and is
 * never drawn as an empty list (an unreachable engine is not "no machines").
 */
data class ClaudeSessionUiState(
    val loading: Boolean = true,
    val unavailable: String? = null,
    val machines: List<MachineRowUi> = emptyList(),
    val tasks: List<TaskRowUi> = emptyList(),
    val sessions: List<SessionRowUi> = emptyList(),
    val draft: String = "",
    /** The machine picked for the next send; null is "the one that's on". */
    val pickedMachine: String? = null,
    val emailMe: Boolean = false,
    /** Send the result as a page on the phone (ticket 09); the Alienware only. */
    val pageMe: Boolean = false,
    val sending: Boolean = false,
    /** The last action's sentence (sent, queued, which machine, not sent). */
    val message: String? = null,
    val expandedTask: Int? = null,
    val answer: String = "",
    val announcementsOn: Boolean = true,
)

/** Pure: the engine's rows to the screen's rows, every sentence from [ClaudeWording]. */
object ClaudeSessionRows {

    fun machines(list: List<ClaudeMachine>, wording: ClaudeWording): List<MachineRowUi> =
        list.map { MachineRowUi(it.name, wording.machineState(it.state, it.lastSeenAt), it.isOnline, it.summariesOnly) }

    fun tasks(
        overview: ClaudeOverview,
        wording: ClaudeWording = ClaudeWording(),
    ): List<TaskRowUi> = overview.tasks.map { t ->
        TaskRowUi(
            id = t.id,
            request = t.request,
            machine = t.machine,
            statusText = statusText(t),
            summary = t.summary,
            question = t.question,
            progress = t.progress.takeIf { t.status == ClaudeTask.RUNNING },
            // A delivered page is opened with its own button, never offered as a file to save.
            files = t.files.filterNot { t.wantsPage && it.isPage }
                .map { FileRowUi(it.id, it.name, ClaudeWording.size(it.size), it.uploaded, it.requested) },
            filesMayCome = !overview.summariesOnly(t),
            needsConfirmation = t.needsConfirmation,
            needsMachine = t.status == ClaudeTask.NEEDS_MACHINE,
            pageTitle = t.pages.firstOrNull()?.title,
            pageWaiting = t.wantsPage && t.pages.isEmpty() && t.status == ClaudeTask.DONE,
            steps = steps(t.steps, wording),
        )
    }

    /** Newest first, each with its time in the phone's zone ("3:40 PM", "yesterday 9:05 PM"). */
    fun steps(list: List<ClaudeStep>, wording: ClaudeWording): List<StepRowUi> =
        ClaudeSteps.newestFirst(list).map { StepRowUi(it.at?.let(wording::clock) ?: "", it.text) }

    /** Waiting on Kevin first (the engine sorts them so too; kept here so the screen cannot drift). */
    fun sessions(list: List<ClaudeCodeSession>): List<SessionRowUi> =
        list.sortedBy { !it.waitingOnYou }.map {
            SessionRowUi(
                machine = it.machine,
                project = it.project.ifBlank { "no project" },
                stateText = if (it.waitingOnYou) "waiting on you" else it.state,
                waiting = it.waitingOnYou,
                summary = it.lastPromptSummary,
            )
        }

    fun statusText(t: ClaudeTask): String = when (t.status) {
        ClaudeTask.QUEUED -> "Queued"
        ClaudeTask.NEEDS_MACHINE -> "Pick a machine"
        ClaudeTask.RUNNING -> "Running"
        ClaudeTask.CANCEL_REQUESTED -> "Stopping"
        ClaudeTask.DONE -> "Done"
        ClaudeTask.FAILED -> "Failed"
        ClaudeTask.NEEDS_CONFIRMATION -> "Waiting for your yes"
        ClaudeTask.CANCELLED -> "Cancelled"
        else -> t.status
    }
}
