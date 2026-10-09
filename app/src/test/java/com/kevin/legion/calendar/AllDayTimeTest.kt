package com.kevin.legion.calendar

import com.kevin.legion.calendar.OpenerCalendarBriefing.BriefingEvent
import com.kevin.legion.engine.dates.DatesAgenda
import com.kevin.legion.service.DatesReminderAlarmReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Voice audit finding 3 (2026-10-05): an all-day event stored at UTC midnight was spoken as
 * "seven this evening" the day BEFORE, and a reminder fired at 7 PM a day early. The fixtures are
 * the real ones: "Buy paddleboard", all-day on Oct 6, stored 2026-10-06T00:00Z, asked on Oct 5.
 */
class AllDayTimeTest {
    private val chicago: ZoneId = ZoneId.of("America/Chicago")
    private val oct6UtcMidnight = LocalDate.of(2026, 10, 6).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private fun chi(m: Int, d: Int, h: Int, min: Int = 0) =
        LocalDateTime.of(2026, m, d, h, min).atZone(chicago).toInstant().toEpochMilli()

    @Test
    fun `utc midnight resolves to the same calendar date, not the evening before`() {
        assertEquals(LocalDate.of(2026, 10, 6), AllDayTime.localDate(oct6UtcMidnight))
        // The bug: read as an instant in Chicago this is Oct 5, 7 PM.
        val asInstant = java.time.Instant.ofEpochMilli(oct6UtcMidnight).atZone(chicago)
        assertEquals(LocalDate.of(2026, 10, 5), asInstant.toLocalDate())
        assertEquals(chi(10, 6, 0), AllDayTime.anchorMs(oct6UtcMidnight, true, chicago))
    }

    @Test
    fun `a timed row is untouched`() {
        assertEquals(oct6UtcMidnight, AllDayTime.anchorMs(oct6UtcMidnight, false, chicago))
        assertEquals(oct6UtcMidnight, AllDayTime.reminderMs(oct6UtcMidnight, false, chicago))
    }

    @Test
    fun `an all-day reminder fires at 9 AM local on its date`() {
        assertEquals(chi(10, 6, 9), AllDayTime.reminderMs(oct6UtcMidnight, true, chicago))
    }

    @Test
    fun `date words name the day with no clock time`() {
        val today = LocalDate.of(2026, 10, 5)
        assertEquals("tomorrow", AllDayTime.dateWords(LocalDate.of(2026, 10, 6), today))
        assertEquals("today", AllDayTime.dateWords(today, today))
        assertEquals("on Friday", AllDayTime.dateWords(LocalDate.of(2026, 10, 9), today))
        assertEquals("on Monday Oct 19", AllDayTime.dateWords(LocalDate.of(2026, 10, 19), today))
    }

    @Test
    fun `opener on Oct 5 says the all-day event is tomorrow and never gives a time`() {
        val start = AllDayTime.anchorMs(oct6UtcMidnight, true, chicago)
        val event = BriefingEvent("Buy paddleboard", start, start, allDay = true)
        val text = OpenerCalendarBriefing.forOpener(listOf(event), chi(10, 5, 13), chicago, hasPermission = true)
        assertTrue(text, text.contains("\"Buy paddleboard\" (all day tomorrow)"))
        assertFalse(text, text.contains("7:00") || text.contains(" PM") || text.contains(" AM"))
    }

    @Test
    fun `an all-day deadline is due today with no clock time`() {
        val start = AllDayTime.anchorMs(oct6UtcMidnight, true, chicago)
        val text = OpenerCalendarBriefing.forOpener(
            emptyList(), chi(10, 6, 8), chicago, hasPermission = true,
            deadlines = listOf(BriefingEvent("Pay rent", start, start, allDay = true)),
        )
        assertTrue(text, text.contains("\"Pay rent\" due today (no time set)"))
        assertFalse(text, text.contains(" PM"))
    }

    @Test
    fun `the reminder wording for an all-day item is a date, a timed item keeps its clock`() {
        val allDay = DatesAgenda.AgendaItem(
            recordId = 1, title = "Buy paddleboard", dueAt = AllDayTime.anchorMs(oct6UtcMidnight, true, chicago),
            endAt = null, location = null, source = "legion", muted = false, dueIsInferred = false, allDay = true,
        )
        assertEquals("today", DatesReminderAlarmReceiver.dueClause(allDay, chicago, LocalDate.of(2026, 10, 6)))
        assertEquals("tomorrow", DatesReminderAlarmReceiver.dueClause(allDay, chicago, LocalDate.of(2026, 10, 5)))
        val timed = allDay.copy(allDay = false, dueAt = chi(10, 6, 19))
        assertEquals("at 7:00 PM", DatesReminderAlarmReceiver.dueClause(timed, chicago, LocalDate.of(2026, 10, 6)))
    }
}
