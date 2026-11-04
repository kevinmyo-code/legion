package com.kevin.legion.claude

import java.time.Duration
import java.time.Instant

/** A task's step history in the order the screen shows it. Pure. */
object ClaudeSteps {
    /**
     * Newest first by time; a step with no time sorts last. Steps with the same time keep the
     * engine's order (it already serves newest first), so equal clocks never shuffle the list.
     */
    fun newestFirst(steps: List<ClaudeStep>): List<ClaudeStep> =
        steps.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<ClaudeStep>> { it.value.at ?: Instant.MIN }.thenBy { it.index },
            )
            .map { it.value }
}

/**
 * How many Claude session tasks are running and how many things are waiting on Kevin, as one quiet
 * Home line and the ongoing notification's text (Kevin 2026-11-03: "I should be able to see how many
 * sessions are running etc."). Pure: the engine's own rows and the clock come in, so a test pins it.
 *
 * **What counts**, from the same rows the Claude session screen reads:
 * - running: tasks the engine says are `running` (it fails one past 30 minutes at read time, so a
 *   stuck task never counts). A `cancel_requested` task is stopping and is not counted.
 * - needs you: a `needs_confirmation` task nobody has answered yet (no newer task names it as its
 *   parent), a `needs_machine` task, and an interactive session `waiting` on him. The two task kinds
 *   count only for [NEEDS_YOU_WINDOW] after they asked, so a question he let go does not sit on
 *   Home for the engine's 30 days; it still waits on the screen.
 */
data class ClaudeWatchSummary(val running: Int, val needsYou: Int, val newestStep: String?) {

    /** "Claude: 3 running, 1 needs you", or null when there is nothing to report. */
    val homeLine: String?
        get() {
            val parts = buildList {
                if (running > 0) add("$running running")
                if (needsYou > 0) add(if (needsYou == 1) "1 needs you" else "$needsYou need you")
            }
            return if (parts.isEmpty()) null else "Claude: " + parts.joinToString(", ")
        }

    /** The ongoing notification's title: only while something runs, else null (no notification). */
    val notificationTitle: String? get() = if (running > 0) homeLine else null

    /** The ongoing notification's text: the newest running task's current step. */
    val notificationText: String? get() = if (running > 0) newestStep ?: NO_STEP_YET else null

    companion object {
        val NOTHING = ClaudeWatchSummary(0, 0, null)
        const val NO_STEP_YET = "No step reported yet."

        /** How long an unanswered question or an unpicked machine counts toward "needs you". */
        val NEEDS_YOU_WINDOW: Duration = Duration.ofHours(24)

        fun from(tasks: List<ClaudeTask>, sessions: List<ClaudeCodeSession>, now: Instant): ClaudeWatchSummary {
            val running = tasks.filter { it.status == ClaudeTask.RUNNING }
            val answered = tasks.mapNotNull { it.parent }.toSet()
            fun recent(at: Instant?): Boolean = at == null || Duration.between(at, now) <= NEEDS_YOU_WINDOW
            val asking = tasks.count {
                when (it.status) {
                    ClaudeTask.NEEDS_CONFIRMATION -> it.id !in answered && recent(it.finishedAt ?: it.createdAt)
                    ClaudeTask.NEEDS_MACHINE -> recent(it.createdAt)
                    else -> false
                }
            }
            val newest = running.maxWithOrNull(compareBy<ClaudeTask> { it.createdAt ?: Instant.MIN }.thenBy { it.id })
            return ClaudeWatchSummary(
                running = running.size,
                needsYou = asking + sessions.count { it.waitingOnYou },
                newestStep = newest?.let { it.progress?.takeIf(String::isNotBlank) ?: it.latestStep },
            )
        }

        fun from(overview: ClaudeOverview, now: Instant): ClaudeWatchSummary =
            from(overview.tasks, overview.status.sessions, now)
    }
}
