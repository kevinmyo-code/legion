package com.kevin.legion.ui.claudesession

import com.kevin.legion.claude.ClaudeOutcome
import com.kevin.legion.claude.ClaudeOverview
import com.kevin.legion.claude.ClaudeSessionController
import com.kevin.legion.claude.ClaudeTask
import com.kevin.legion.claude.ClaudeWording
import com.kevin.legion.claude.LaptopHealthWording
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The task dialog over the app (Kevin 2026-11-04): the request in a few words, the machine, the
 * status in words, the current step, the kept steps, and once finished the summary or the question.
 * [unavailable] is the last read's failure in words; the task drawn is then the last one read.
 */
data class ClaudeTaskModalUi(
    val taskId: Int,
    val request: String,
    val machineText: String,
    val statusText: String,
    val currentStep: String?,
    val steps: List<StepRowUi>,
    /** "Summary: ..." or "Question: ..." once finished; null while it runs. */
    val outcome: String?,
    /** What to do next when the task is waiting on Kevin, in words; null otherwise. */
    val nextStep: String?,
    val finished: Boolean,
    val unavailable: String? = null,
)

/** One row of the header's Claude sheet: a task or an interactive session, one or two lines. */
data class ClaudeSheetRowUi(
    /** Set for a task (tap opens its dialog); null for an interactive session. */
    val taskId: Int?,
    val machine: String,
    val doing: String,
    val statusText: String,
    val age: String,
)

/** What the header sheet draws: the rows, or the read's failure in words. */
data class ClaudeSheetUi(
    val loading: Boolean = true,
    val unavailable: String? = null,
    val rows: List<ClaudeSheetRowUi> = emptyList(),
    /** One line per machine that sends health (laptop-health 2026-11-10), above the rows. */
    val laptops: List<String> = emptyList(),
)

/** Pure: the engine's rows to the dialog and the sheet. Every status word from [ClaudeSessionRows]. */
object ClaudeQuickRows {
    const val REQUEST_WORDS = 10
    const val SHEET_TASKS = 10
    private val WHITESPACE = Regex("""\s+""")
    private const val NEXT_ANSWER =
        "Nothing on its ask list has run. Answer by voice or on the Claude sessions screen."
    private const val NEXT_MACHINE = "Nothing has run. Pick a machine by voice or on the Claude sessions screen."

    /** The first [REQUEST_WORDS] words, with "..." when more were said. */
    fun fewWords(request: String): String {
        val words = request.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val shown = words.take(REQUEST_WORDS).joinToString(" ")
        return if (words.size <= REQUEST_WORDS) shown else "$shown..."
    }

    fun modal(t: ClaudeTask, wording: ClaudeWording): ClaudeTaskModalUi = ClaudeTaskModalUi(
        taskId = t.id,
        request = fewWords(t.request),
        machineText = t.machine?.let { "On the $it" } ?: "No machine picked yet",
        statusText = ClaudeSessionRows.statusText(t),
        currentStep = currentStep(t),
        steps = ClaudeSessionRows.steps(t.steps, wording),
        outcome = outcome(t),
        nextStep = when (t.status) {
            ClaudeTask.NEEDS_CONFIRMATION -> NEXT_ANSWER
            ClaudeTask.NEEDS_MACHINE -> NEXT_MACHINE
            else -> null
        },
        finished = t.isFinished,
    )

    /** The live step while it runs, else null. */
    private fun currentStep(t: ClaudeTask): String? {
        val running = t.status == ClaudeTask.RUNNING || t.status == ClaudeTask.CANCEL_REQUESTED
        return if (running) t.progress?.takeIf(String::isNotBlank) ?: t.latestStep else null
    }

    /** "Summary: ..." or "Question: ..." once finished; null while it runs or waits. */
    private fun outcome(t: ClaudeTask): String? {
        val summary = t.summary?.trim()?.takeIf { it.isNotEmpty() }
        val question = t.question?.trim()?.takeIf { it.isNotEmpty() }
        return when (t.status) {
            ClaudeTask.NEEDS_CONFIRMATION -> "Question: " + (question ?: summary ?: "it needs your yes.")
            ClaudeTask.DONE, ClaudeTask.FAILED, ClaudeTask.CANCELLED ->
                summary?.let { "Summary: $it" } ?: question?.let { "Question: $it" } ?: "No summary was given."
            else -> null
        }
    }

    /** Newest tasks first (the engine's order), then the interactive sessions, waiting on Kevin first. */
    /** "Alienware: online, CPU 61 C, ..." for each machine that sends health. */
    fun laptops(o: ClaudeOverview, wording: ClaudeWording): List<String> =
        o.status.machines.mapNotNull { LaptopHealthWording.summary(it, wording) }

    fun sheet(o: ClaudeOverview, wording: ClaudeWording): List<ClaudeSheetRowUi> {
        val tasks = o.tasks.take(SHEET_TASKS).map { t ->
            val step = if (t.status == ClaudeTask.RUNNING) currentStep(t) else null
            ClaudeSheetRowUi(
                taskId = t.id,
                machine = t.machine ?: "No machine yet",
                doing = step ?: fewWords(t.request),
                statusText = ClaudeSessionRows.statusText(t),
                age = wording.age(t.createdAt),
            )
        }
        val sessions = o.status.sessions.sortedBy { !it.waitingOnYou }.map { s ->
            val project = s.project.ifBlank { "no project" }
            ClaudeSheetRowUi(
                taskId = null,
                machine = s.machine,
                doing = s.lastPromptSummary?.let { "$project: $it" } ?: project,
                statusText = if (s.waitingOnYou) "Waiting on you" else "Session " + s.state,
                age = wording.age(s.lastSeenAt),
            )
        }
        return tasks + sessions
    }

    /**
     * Reads task [id] every [every] and emits each outcome, until the task is finished and has no
     * page still on its way. Cold: it reads only while collected, so the dialog polls only while it
     * is drawn and the app is started.
     */
    fun follow(
        controller: ClaudeSessionController,
        id: Int,
        every: Duration,
    ): Flow<ClaudeOutcome<ClaudeTask>> = flow {
        while (true) {
            val out = controller.task(id)
            emit(out)
            val settled = out is ClaudeOutcome.Ok && out.value.isFinished &&
                !out.value.pagePending && !out.value.filesPending
            if (settled) break
            delay(every.toMillis())
        }
    }
}
