package com.kevin.legion.calendar

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the pure logic behind `read_calendar` (ticket 19,
 * `.scratch/google-account-integration/issues/19-calendar-read-tool.md`) - plain JUnit, no
 * `Context`/`CalendarContract`, same posture as [com.kevin.legion.ui.notes.CalendarAgendaResolverTest].
 * Fixed UTC zone throughout so the millis assertions are not machine-dependent.
 */
class CalendarReadToolLogicTest {

    private val zone = ZoneOffset.UTC

    // -------------------------------------------------------------------------------- parseWindow

    @Test
    fun `parseWindow on a single day covers the whole day, not a zero-width window`() {
        val window = CalendarReadToolLogic.parseWindow("2026-08-13", "2026-08-13", zone)!!
        val expectedStart = LocalDate.of(2026, 8, 13).atStartOfDay(zone).toInstant().toEpochMilli()
        val expectedEnd = LocalDate.of(2026, 8, 14).atStartOfDay(zone).toInstant().toEpochMilli() - 1

        assertEquals(expectedStart, window.first)
        assertEquals(expectedEnd, window.second)
        assertTrue(window.second > window.first)
    }

    @Test
    fun `parseWindow across multiple days spans midnight of from to just before the day after to`() {
        val window = CalendarReadToolLogic.parseWindow("2026-08-13", "2026-08-20", zone)!!
        val expectedStart = LocalDate.of(2026, 8, 13).atStartOfDay(zone).toInstant().toEpochMilli()
        val expectedEnd = LocalDate.of(2026, 8, 21).atStartOfDay(zone).toInstant().toEpochMilli() - 1

        assertEquals(expectedStart, window.first)
        assertEquals(expectedEnd, window.second)
    }

    @Test
    fun `parseWindow with to before from is null, never a guessed window`() {
        assertNull(CalendarReadToolLogic.parseWindow("2026-08-20", "2026-08-13", zone))
    }

    @Test
    fun `parseWindow with a malformed from date is null`() {
        assertNull(CalendarReadToolLogic.parseWindow("not-a-date", "2026-08-13", zone))
    }

    @Test
    fun `parseWindow with a malformed to date is null`() {
        assertNull(CalendarReadToolLogic.parseWindow("2026-08-13", "not-a-date", zone))
    }

    @Test
    fun `parseWindow with a blank arg is null`() {
        assertNull(CalendarReadToolLogic.parseWindow("", "2026-08-13", zone))
        assertNull(CalendarReadToolLogic.parseWindow("2026-08-13", "", zone))
    }

    // -------------------------------------------------------------------- permission-missing wording

    @Test
    fun `permission missing message names the screen that grants it, never an empty result`() {
        // The exact failure this guards against (ticket 19 point 3, same L15 shape as ticket 13
        // point 7): a refused READ_CALENDAR must produce WORDS, not an empty events array that
        // reads as "you have nothing on".
        assertTrue(CalendarReadToolLogic.PERMISSION_MISSING_MESSAGE.contains("Today", ignoreCase = false))
        assertTrue(CalendarReadToolLogic.PERMISSION_MISSING_MESSAGE.isNotBlank())
    }

    @Test
    fun `invalid window message is distinct from the permission missing message`() {
        assertTrue(CalendarReadToolLogic.INVALID_WINDOW_MESSAGE != CalendarReadToolLogic.PERMISSION_MISSING_MESSAGE)
        assertTrue(CalendarReadToolLogic.INVALID_WINDOW_MESSAGE.isNotBlank())
    }
}
