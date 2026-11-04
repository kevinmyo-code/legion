package com.kevin.legion.claude

import com.kevin.legion.claude.FakeClaudeBackend.Companion.NOW
import com.kevin.legion.claude.FakeClaudeBackend.Companion.task
import com.kevin.legion.ui.claudesession.ClaudeSessionRows
import com.kevin.legion.ui.claudesession.StepRowUi
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ClaudeWatchSummary]: the Home line and the ongoing notification's text from the engine's rows
 * (Kevin 2026-11-03), and [ClaudeSteps]'s newest-first order for the step list.
 */
class ClaudeWatchSummaryTest {

    private fun session(state: String) = ClaudeCodeSession("Alienware", "legion", state, null, NOW)

    private fun at(minutesAgo: Long): Instant = NOW.minus(Duration.ofMinutes(minutesAgo))

    private fun step(minutesAgo: Long?, text: String) = ClaudeStep(minutesAgo?.let(::at), text)

    @Test
    fun `nothing running and nothing asking is no line and no notification`() {
        val s = ClaudeWatchSummary.from(
            listOf(task(1, ClaudeTask.DONE), task(2, ClaudeTask.QUEUED), task(3, ClaudeTask.FAILED)),
            listOf(session("working"), session("idle")),
            NOW,
        )
        assertEquals(ClaudeWatchSummary(0, 0, null), s)
        assertNull(s.homeLine)
        assertNull(s.notificationTitle)
        assertNull(s.notificationText)
    }

    @Test
    fun `three running and one question is the line, and the notification shows the newest step`() {
        val tasks = listOf(
            task(44, ClaudeTask.RUNNING, progress = "writing the summary").copy(createdAt = at(1)),
            task(43, ClaudeTask.RUNNING, progress = "reading Downloads").copy(createdAt = at(5)),
            task(42, ClaudeTask.RUNNING).copy(createdAt = at(9)),
            task(41, ClaudeTask.NEEDS_CONFIRMATION, question = "Delete the old copy?").copy(finishedAt = at(3)),
        )
        val s = ClaudeWatchSummary.from(tasks, emptyList(), NOW)
        assertEquals("Claude: 3 running, 1 needs you", s.homeLine)
        assertEquals("Claude: 3 running, 1 needs you", s.notificationTitle)
        assertEquals("writing the summary", s.notificationText)
    }

    @Test
    fun `only waiting things is a Home line but no notification`() {
        val s = ClaudeWatchSummary.from(
            listOf(task(5, ClaudeTask.NEEDS_MACHINE, machine = null)),
            listOf(session("waiting")),
            NOW,
        )
        assertEquals("Claude: 2 need you", s.homeLine)
        assertNull(s.notificationTitle)
        assertNull(s.notificationText)
    }

    @Test
    fun `an answered question does not need him, and neither does one older than a day`() {
        val tasks = listOf(
            task(52, ClaudeTask.QUEUED).copy(parent = 50, confirms = true),
            task(51, ClaudeTask.NEEDS_CONFIRMATION).copy(finishedAt = at(Duration.ofHours(25).toMinutes())),
            task(50, ClaudeTask.NEEDS_CONFIRMATION).copy(finishedAt = at(10)),
            task(49, ClaudeTask.NEEDS_MACHINE).copy(createdAt = at(Duration.ofHours(30).toMinutes())),
        )
        val s = ClaudeWatchSummary.from(tasks, emptyList(), NOW)
        assertEquals(0, s.needsYou)
        assertNull(s.homeLine)
    }

    @Test
    fun `a stopping task is not counted as running`() {
        val s = ClaudeWatchSummary.from(
            listOf(task(7, ClaudeTask.CANCEL_REQUESTED, progress = "x"), task(8, ClaudeTask.RUNNING)),
            emptyList(),
            NOW,
        )
        assertEquals("Claude: 1 running", s.homeLine)
    }

    @Test
    fun `with no current line the newest kept step stands in, and with neither it says so`() {
        val withHistory = task(9, ClaudeTask.RUNNING).copy(
            steps = listOf(step(1, "opening wiring.pdf"), step(4, "reading Downloads")),
        )
        assertEquals(
            "opening wiring.pdf",
            ClaudeWatchSummary.from(listOf(withHistory), emptyList(), NOW).notificationText,
        )
        assertEquals(
            ClaudeWatchSummary.NO_STEP_YET,
            ClaudeWatchSummary.from(listOf(task(10, ClaudeTask.RUNNING)), emptyList(), NOW).notificationText,
        )
    }

    @Test
    fun `steps are newest first, an untimed step last, and equal times keep their order`() {
        val steps = listOf(
            step(10, "reading Downloads"),
            step(null, "untimed"),
            step(1, "writing the summary"),
            step(5, "opening a"),
            step(5, "opening b"),
        )
        assertEquals(
            listOf("writing the summary", "opening a", "opening b", "reading Downloads", "untimed"),
            ClaudeSteps.newestFirst(steps).map { it.text },
        )
        assertEquals("writing the summary", task(1, ClaudeTask.DONE).copy(steps = steps).latestStep)
    }

    @Test
    fun `the screen's step rows are newest first with the phone's clock`() {
        val wording = ClaudeWording(now = { NOW }, zone = { ZoneOffset.UTC })
        val rows = ClaudeSessionRows.steps(
            listOf(ClaudeStep(Instant.parse("2026-11-01T19:40:00Z"), "reading Downloads"),
                ClaudeStep(Instant.parse("2026-11-01T19:52:00Z"), "found it")),
            wording,
        )
        assertEquals(listOf(StepRowUi("7:52 PM", "found it"), StepRowUi("7:40 PM", "reading Downloads")), rows)
    }
}
