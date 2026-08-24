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
        assertTrue(briefing.contains("nothing at all"))
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

        assertTrue(briefing.contains("\"Public holiday\" (all day)"))
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
