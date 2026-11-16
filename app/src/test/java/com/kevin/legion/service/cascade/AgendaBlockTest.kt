package com.kevin.legion.service.cascade

import com.kevin.legion.calendar.DueInput
import com.kevin.legion.calendar.DueList
import com.kevin.legion.calendar.DueSource
import com.kevin.legion.calendar.DueSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * [AgendaBlock] (ADR 0093, 2026-11-16 widening): today's events, work due today and overdue work
 * from the phone's own read (the one `day read_calendar` makes), each as facts, EMPTY or COULD NOT BE
 * READ; the as-of time; the caps; the staleness rules. Invented data only.
 */
class AgendaBlockTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2099, 3, 3)

    /** 09:05 UTC on the invented day. */
    private val readAt = ZonedDateTime.of(2099, 3, 3, 9, 5, 0, 0, zone).toInstant().toEpochMilli()

    private fun at(day: LocalDate, h: Int, m: Int) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun task(title: String, day: LocalDate, h: Int = 23, m: Int = 59, done: Boolean = false) = DueInput(
        title = title,
        atMs = at(day, h, m),
        allDay = false,
        date = day,
        done = done,
        source = DueSource.CALENDAR,
        whenLabel = "%02d:%02d".format(h, m),
    )

    /** The tool's own summariser over [inputs], for today only, as `CalendarWindowRead.today` reads it. */
    private fun due(inputs: List<DueInput>, unreadable: List<String> = emptyList()): DueSummary =
        DueList.summarize(inputs, today, today, today, readAt, unreadable)

    private val seminar =
        AgendaBlock.AgendaEvent("Invented seminar", at(today, 14, 0), at(today, 15, 0), false, "Room 4")

    private val eventsLabel = "Today's calendar events"
    private val dueLabel = "Open work due today, calendar tasks and dated to-dos"
    private val late = "Overdue open work (past due, not done)"
    private val unread = "(COULD NOT BE READ, which is not the same as empty)"

    private fun render(
        events: List<AgendaBlock.AgendaEvent> = emptyList(),
        inputs: List<DueInput> = emptyList(),
        unreadable: List<String> = emptyList(),
    ) = AgendaBlock.render(AgendaBlock.Snapshot(events, due(inputs, unreadable), readAt), zone)

    @Test
    fun `events, work due today and overdue work are listed, with the time it was read`() {
        val block = render(
            events = listOf(seminar),
            inputs = listOf(
                task("Invented worksheet", today),
                task("Invented lab notes", today.minusDays(2)),
            ),
        )
        assertTrue(block, block.contains("read from the phone's own calendar and to-do list at 09:05"))
        assertTrue(block.contains("today is Tuesday 2099-03-03"))
        assertTrue(
            block.contains(
                "$eventsLabel (facts): 1 on the calendar.\n    * Invented seminar, 14:00 to 15:00, at Room 4",
            ),
        )
        // Due work in the tool's own words (DueList), overdue said as overdue.
        assertTrue(
            block.contains(
                "1 open and due, not done, not overdue yet: Invented worksheet (due today, Tue 23:59, calendar).",
            ),
        )
        assertTrue(block.contains("$late (facts): 1 OVERDUE (past due, not done): Invented lab notes"))
    }

    @Test
    fun `work due earlier today that has passed is overdue, not due later`() {
        val block = render(inputs = listOf(task("Invented quiz", today, 8, 0)))
        assertTrue(
            block,
            block.contains("1 OVERDUE (past due, not done): Invented quiz (due today, Tue 08:00, calendar)"),
        )
        assertTrue(block.contains("$dueLabel (EMPTY: read, and nothing open is due later today)."))
    }

    @Test
    fun `an empty day says EMPTY for each part and never clear or free`() {
        val block = render()
        assertTrue(block, block.contains("$eventsLabel (EMPTY: read, and nothing is on the calendar today)."))
        assertTrue(block.contains("(EMPTY: read, and nothing open is due later today)."))
        assertTrue(block.contains("Overdue open work (past due, not done) (EMPTY: read, and nothing is overdue)."))
        for (word in listOf("clear", "free", "nothing due")) {
            assertFalse("$word in $block", block.lowercase().contains(word))
        }
    }

    @Test
    fun `an unread source is never passed off as empty`() {
        val both = render(unreadable = listOf("calendar tasks", "to-dos"))
        assertTrue(both, both.contains("$dueLabel $unread"))
        assertTrue(both.contains("$late $unread"))
        assertFalse(both.contains("EMPTY: read, and nothing is overdue"))

        val one = render(inputs = listOf(task("Invented essay", today.minusDays(1))), unreadable = listOf("to-dos"))
        assertTrue(
            one,
            one.contains("(facts; PARTLY READ: Could not read to-dos, so this may be missing items: say so.)"),
        )
        assertTrue(one.contains("$dueLabel (PARTLY READ, which is not the same as empty)"))
        assertFalse(one.contains("nothing is overdue"))
        assertFalse(one.contains("nothing open is due later today"))
    }

    @Test
    fun `thirty overdue items are capped with an honest count, and due today is not crowded out`() {
        val overdue = (1..30).map { task("Invented assignment $it", today.minusDays((it % 20 + 1).toLong())) }
        val block = render(inputs = overdue + task("Invented reading", today))
        assertTrue(block, block.contains("30 OVERDUE (past due, not done):"))
        val more = AgendaBlock.MAX_DUE_LISTED.let { 30 - it }
        assertTrue(block.contains("; ${DueList.moreLine(more)}."))
        assertTrue(block.contains("and 22 more not listed here (ask day read_calendar for the full list)"))
        // The cap is per part: the one item due today is still listed.
        assertTrue(block.contains("1 open and due, not done, not overdue yet: Invented reading"))
        val listed = overdue.count { block.contains("${it.title} (due") }
        assertEquals(AgendaBlock.MAX_DUE_LISTED, listed)
        // Bounded: the block with 30 overdue rows stays small.
        assertTrue("block is ${block.length} chars", block.length < 4_000)
    }

    @Test
    fun `many events are capped with an honest count`() {
        val events = (1..15).map {
            AgendaBlock.AgendaEvent("Invented meeting $it", at(today, 8, it), at(today, 8, it + 1), false)
        }
        val block = render(events = events)
        assertTrue(block, block.contains("(facts): 15 on the calendar."))
        assertTrue(block.contains("    * ${DueList.moreLine(15 - AgendaBlock.MAX_EVENTS)}"))
        assertFalse(block.contains("Invented meeting 13,"))
    }

    @Test
    fun `an event already over is marked, an all-day one says so`() {
        val early = AgendaBlock.AgendaEvent("Invented breakfast", at(today, 7, 0), at(today, 8, 0), false)
        val allDay = AgendaBlock.AgendaEvent("Invented holiday", at(today, 0, 0), at(today, 0, 0), true)
        val block = render(events = listOf(early, allDay))
        assertTrue(block, block.contains("Invented breakfast, 07:00 to 08:00 (already over when read)"))
        assertTrue(block.contains("Invented holiday, all day"))
    }

    @Test
    fun `the block may answer today, due and overdue, and sends everything else to the tool`() {
        val block = render(inputs = listOf(task("Invented essay", today)))
        assertTrue(block.contains("It is what day read_calendar returns for today, read at 09:05"))
        assertTrue(block.contains("what he has today, what is due today, what is overdue, what is on his calendar"))
        assertTrue(block.contains("may be answered from this block without a tool call"))
        assertTrue(block.contains("you may say it is as of 09:05"))
        assertTrue(block.contains("A plain \"what's due\" with no day named means due today plus overdue"))
        assertTrue(block.contains("include the overdue work"))
        assertTrue(block.contains("say how many"))
        assertTrue(block.contains("Call day read_calendar for any other day or range (tomorrow, this week)"))
        assertTrue(block.contains("for work due after today"))
        assertTrue(block.contains("for anything not in this block"))
        assertTrue(block.contains("(complete, add, move, delete) goes through its tool"))
        // The 2026-11-16 narrowing is gone: overdue work is in the block now.
        assertFalse(block.contains("Overdue work is NOT in this block"))
        assertFalse(block.contains("never answer those from this block"))
        assertFalse(block.contains("household engine"))
        assertTrue(block.contains(AgendaBlock.WEATHER_LINE))
    }

    @Test
    fun `done work is counted, not listed`() {
        val block = render(inputs = listOf(task("Invented draft", today, done = true)))
        assertTrue(block, block.contains("- Already done today: 1."))
        assertFalse(block.contains("Invented draft"))
    }

    @Test
    fun `withdrawn, expired and unavailable say nothing about his day and point at the tool`() {
        val gone = AgendaBlock.withdrawn(LocalTime.of(10, 7))
        assertTrue(gone.contains("10:07"))
        assertTrue(gone.contains("out of date"))
        assertTrue(gone.contains("day read_calendar"))
        val old = AgendaBlock.expired(LocalTime.of(9, 5))
        assertTrue(old.contains("09:05"))
        assertTrue(old.contains("day read_calendar"))
        val none = AgendaBlock.unavailable(AgendaBlock.NOT_READ_IN_TIME)
        assertTrue(none.contains("says nothing about his day"))
        assertTrue(none.contains(AgendaBlock.NOT_READ_IN_TIME))
        assertTrue(none.contains("day read_calendar"))
        assertTrue(AgendaBlock.TOOL_NOTE.contains("out of date"))
    }

    @Test
    fun `current keeps a fresh read, withdraws after a write, ages out after 30 minutes or at midnight`() {
        val block = "TODAY'S AGENDA, invented."
        assertEquals("", AgendaBlock.current("", readAt, null, readAt, zone))
        assertEquals(block, AgendaBlock.current(block, readAt, null, readAt + 29 * 60_000L, zone))
        assertEquals(
            AgendaBlock.withdrawn(LocalTime.of(9, 10)),
            AgendaBlock.current(block, readAt, LocalTime.of(9, 10), readAt + 60_000L, zone),
        )
        assertEquals(
            AgendaBlock.expired(LocalTime.of(9, 5)),
            AgendaBlock.current(block, readAt, null, readAt + AgendaBlock.MAX_AGE_MS + 1, zone),
        )
        // Read at 23:50, asked at 00:05 the next day: another day, not used.
        val late = ZonedDateTime.of(2099, 3, 3, 23, 50, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(
            AgendaBlock.expired(LocalTime.of(23, 50)),
            AgendaBlock.current(block, late, null, late + 15 * 60_000L, zone),
        )
        // An "unavailable" line has no read time and stays as it is.
        val none = AgendaBlock.unavailable(AgendaBlock.READ_FAILED)
        assertEquals(none, AgendaBlock.current(none, 0L, null, readAt + AgendaBlock.MAX_AGE_MS * 2, zone))
    }
}
