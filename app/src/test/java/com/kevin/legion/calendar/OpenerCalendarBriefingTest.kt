package com.kevin.legion.calendar

import com.kevin.legion.calendar.OpenerCalendarBriefing.BriefingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Guards the opener's calendar sentence - plain JUnit, no `Context`/`CalendarContract`, same
 * posture as [com.kevin.legion.ui.notes.CalendarAgendaResolverTest]. All fixtures invented.
 *
 * The case this file exists for is [`no permission never reads as a clear day`]: an unreadable
 * calendar and an empty one must produce different sentences, because collapsing them is how a
 * greeting tells the user they are free when it has no idea.
 */
class OpenerCalendarBriefingTest {

    private val zone: ZoneId = ZoneId.of("America/Chicago")

    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 8, 21, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun event(
        id: Long,
        title: String,
        startMs: Long,
        endMs: Long = startMs + 60L * 60L * 1000L,
        allDay: Boolean = false,
    ) = BriefingEvent(title = title, startMs = startMs, endMs = endMs, allDay = allDay)

    @Test
    fun `no permission never reads as a clear day`() {
        val briefing = OpenerCalendarBriefing.forOpener(emptyList(), at(9), zone, hasPermission = false)

        assertEquals(OpenerCalendarBriefing.NO_PERMISSION, briefing)
        assertFalse(briefing == OpenerCalendarBriefing.NOTHING_SCHEDULED)
    }

    @Test
    fun `empty calendar says nothing is on and forbids naming one`() {
        val briefing = OpenerCalendarBriefing.forOpener(emptyList(), at(9), zone, hasPermission = true)

        assertEquals(OpenerCalendarBriefing.NOTHING_SCHEDULED, briefing)
        // Changed deliberately 2026-09-27. This used to assert "nothing at all", the phrasing the
        // model spoke as "your schedule is entirely free" to a student with an assignment due that
        // night. It now states only what was checked, and forbids the generalisation.
        assertTrue(briefing.contains("nothing due today"))
        assertTrue(briefing.contains("do not call their day, week or schedule free"))
    }

    @Test
    fun `a deadline tonight is stated, and the opener may not call the day free`() {
        // The 2026-09-27 case exactly: 10 AM, no appointments, COSC 4320 due 11:59 PM.
        val due = listOf(event(1, "COSC 4320 · Assignment 3", at(23, 59), at(23, 59)))
        val briefing = OpenerCalendarBriefing.forOpener(emptyList(), at(10), zone, hasPermission = true, deadlines = due)

        assertFalse(briefing == OpenerCalendarBriefing.NOTHING_SCHEDULED)
        assertTrue(briefing.contains("COSC 4320 · Assignment 3"))
        assertTrue(briefing.contains("11:59 PM"))
        assertTrue(briefing.contains("Never say they are free"))
    }

    @Test
    fun `appointments and a deadline are both stated`() {
        val appts = listOf(event(2, "Dentist", at(14), at(15)))
        val due = listOf(event(3, "MATH 3391 · Quiz", at(23, 59), at(23, 59)))
        val briefing = OpenerCalendarBriefing.forOpener(appts, at(10), zone, hasPermission = true, deadlines = due)

        assertTrue(briefing.contains("\"Dentist\" at 2:00 PM"))
        assertTrue(briefing.contains("MATH 3391 · Quiz"))
    }

    @Test
    fun `no permission still forbids the subject even with deadlines passed`() {
        val due = listOf(event(4, "X", at(23, 59), at(23, 59)))
        val briefing = OpenerCalendarBriefing.forOpener(emptyList(), at(10), zone, hasPermission = false, deadlines = due)
        assertEquals(OpenerCalendarBriefing.NO_PERMISSION, briefing)
    }

    @Test
    fun `more deadlines than the cap are counted, not silently dropped`() {
        val due = (1..6).map { event(it.toLong(), "Task $it", at(20, it), at(20, it)) }
        val briefing = OpenerCalendarBriefing.forOpener(emptyList(), at(10), zone, hasPermission = true, deadlines = due)
        assertTrue(briefing.contains("(and 2 more)"))
    }

    @Test
    fun `events are listed with their real titles and times`() {
        val briefing = OpenerCalendarBriefing.forOpener(
            listOf(event(1, "Standup", at(10))), at(9), zone, hasPermission = true,
        )

        assertTrue(briefing.contains("\"Standup\" at 10:00 AM"))
        assertTrue(briefing.contains("it does not exist"))
    }

    @Test
    fun `events already finished are dropped`() {
        val briefing = OpenerCalendarBriefing.forOpener(
            listOf(event(1, "Breakfast", at(7), endMs = at(8))), at(9), zone, hasPermission = true,
        )

        assertEquals(OpenerCalendarBriefing.NOTHING_SCHEDULED, briefing)
    }

    @Test
    fun `all-day events survive the finished-event filter`() {
        val briefing = OpenerCalendarBriefing.forOpener(
            listOf(event(1, "Public holiday", at(0), endMs = at(0), allDay = true)),
            at(9), zone, hasPermission = true,
        )

        assertTrue(briefing.contains("\"Public holiday\" (all day today)"))
    }

    @Test
    fun `events come out in start order and are capped`() {
        val events = (1..6).map { event(it.toLong(), "Meeting $it", at(9 + it)) }.reversed()

        val briefing = OpenerCalendarBriefing.forOpener(events, at(9), zone, hasPermission = true)

        assertTrue(briefing.indexOf("Meeting 1") < briefing.indexOf("Meeting 2"))
        assertTrue(briefing.contains("Meeting ${OpenerCalendarBriefing.MAX_EVENTS}"))
        assertFalse(briefing.contains("Meeting ${OpenerCalendarBriefing.MAX_EVENTS + 1}"))
    }

    @Test
    fun `a blank title never leaves an empty quote`() {
        val briefing = OpenerCalendarBriefing.forOpener(
            listOf(event(1, "   ", at(10))), at(9), zone, hasPermission = true,
        )

        assertTrue(briefing.contains("\"(untitled)\""))
    }
}
