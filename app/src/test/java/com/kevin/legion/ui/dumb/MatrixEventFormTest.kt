package com.kevin.legion.ui.dumb

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixEventFormTest {
    private val zone = ZoneId.of("America/Chicago")

    private fun form(title: String = "Dentist", date: String = "2026-10-24", time: String = "") =
        MatrixEventForm(id = null, title = title, date = date, time = time)

    @Test
    fun `no time is an all-day event at UTC midnight lasting a day`() {
        val ok = form().parse(zone) as MatrixEventForm.Parsed.Ok
        val utcMidnight = LocalDate.of(2026, 10, 24).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertTrue(ok.times.allDay)
        assertEquals(utcMidnight, ok.times.startMs)
        assertEquals(utcMidnight + 24L * 3_600_000L, ok.times.endMs)
    }

    @Test
    fun `a time is a timed event in the device zone, an hour long by default`() {
        val ok = form(time = "14:30").parse(zone) as MatrixEventForm.Parsed.Ok
        val expected = LocalDate.of(2026, 10, 24).atTime(14, 30).atZone(zone).toInstant().toEpochMilli()
        assertFalse(ok.times.allDay)
        assertEquals(expected, ok.times.startMs)
        assertEquals(expected + 3_600_000L, ok.times.endMs)
    }

    @Test
    fun `an edited event keeps its own length`() {
        val ok = form(time = "09:00").copy(durationMs = 90 * 60_000L).parse(zone) as MatrixEventForm.Parsed.Ok
        assertEquals(90 * 60_000L, ok.times.endMs - ok.times.startMs)
    }

    @Test
    fun `each bad field is refused with a sentence that says nothing was saved`() {
        val noTitle = form(title = "  ").parse(zone) as MatrixEventForm.Parsed.Bad
        assertTrue(noTitle.sentence.contains("title") && noTitle.sentence.contains("Nothing was saved"))
        val badDate = form(date = "24/10/2026").parse(zone) as MatrixEventForm.Parsed.Bad
        assertTrue(badDate.sentence.contains("2026-10-24") && badDate.sentence.contains("Nothing was saved"))
        val badTime = form(time = "2pm").parse(zone) as MatrixEventForm.Parsed.Bad
        assertTrue(badTime.sentence.contains("14:30") && badTime.sentence.contains("Nothing was saved"))
    }

    @Test
    fun `the form of an existing all-day event shows its UTC date and no time`() {
        val utcMidnight = LocalDate.of(2026, 10, 24).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val row = MatrixAgendaRow(7, MatrixAgendaKind.EVENT, "Holiday", utcMidnight, utcMidnight + 86_400_000L, true)
        val f = MatrixEventForm.of(row, zone)
        assertEquals("2026-10-24", f.date)
        assertEquals("", f.time)
        assertEquals(7L, f.id)
    }

    @Test
    fun `the form of an existing timed event round-trips through parse`() {
        val start = LocalDate.of(2026, 10, 24).atTime(8, 15).atZone(zone).toInstant().toEpochMilli()
        val row = MatrixAgendaRow(9, MatrixAgendaKind.EVENT, "Run", start, start + 1_800_000L, false)
        val parsed = MatrixEventForm.of(row, zone).parse(zone) as MatrixEventForm.Parsed.Ok
        assertEquals(start, parsed.times.startMs)
        assertEquals(start + 1_800_000L, parsed.times.endMs)
    }
}
