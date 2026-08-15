package com.kevin.legion.ui.notes

import com.kevin.legion.calendar.CalendarProvider.GoogleCalendarEvent
import com.kevin.legion.ui.AgendaEntry
import com.kevin.legion.ui.AgendaSource
import com.kevin.legion.ui.common.dailyBuckets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Exercises the pure merge/sort/empty-state logic in `ui/notes/CalendarAgendaResolver.kt` - plain
 * JUnit, no Compose/Room/`Context`/`CalendarContract`, same posture as
 * [com.kevin.legion.ui.notes.NotesResolversTest]. All fixtures invented (ticket 13).
 */
class CalendarAgendaResolverTest {

    private fun googleEvent(id: Long, title: String, startMs: Long, allDay: Boolean = false) =
        GoogleCalendarEvent(eventId = id, calendarId = 1L, title = title, startMs = startMs, endMs = startMs + 1_000L, allDay = allDay)

    // ------------------------------------------------------------------------------------- mergeAgenda

    @Test
    fun `mergeAgenda combines local and google rows sorted by start time`() {
        val local = listOf(
            AgendaEntry("Oil change", timeMs = 3_000L, allDay = false),
            AgendaEntry("Water the plants", timeMs = 1_000L, allDay = false),
        )
        val google = listOf(googleEvent(1, "Dentist", startMs = 2_000L))

        val merged = mergeAgenda(local, google)

        assertEquals(listOf("Water the plants", "Dentist", "Oil change"), merged.map { it.label })
        assertEquals(listOf(1_000L, 2_000L, 3_000L), merged.map { it.timeMs })
    }

    @Test
    fun `mergeAgenda tags google rows GOOGLE and leaves local rows LOCAL`() {
        val local = listOf(AgendaEntry("Water the plants", timeMs = 1_000L, allDay = false))
        val google = listOf(googleEvent(1, "Dentist", startMs = 2_000L))

        val merged = mergeAgenda(local, google)

        assertEquals(AgendaSource.LOCAL, merged.first { it.label == "Water the plants" }.source)
        assertEquals(AgendaSource.GOOGLE, merged.first { it.label == "Dentist" }.source)
    }

    @Test
    fun `mergeAgenda carries allDay through from the google event`() {
        val merged = mergeAgenda(emptyList(), listOf(googleEvent(1, "Kevin's birthday", startMs = 5_000L, allDay = true)))
        assertTrue(merged.single().allDay)
    }

    @Test
    fun `mergeAgenda with nothing on either side is empty, not a crash`() {
        assertTrue(mergeAgenda(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `mergeAgenda with only local rows returns them unchanged in order`() {
        val local = listOf(
            AgendaEntry("A", timeMs = 1_000L, allDay = false),
            AgendaEntry("B", timeMs = 2_000L, allDay = false),
        )
        assertEquals(local, mergeAgenda(local, emptyList()))
    }

    @Test
    fun `mergeAgenda with only google rows returns them sorted and tagged`() {
        val google = listOf(
            googleEvent(2, "Later", startMs = 5_000L),
            googleEvent(1, "Earlier", startMs = 1_000L),
        )
        val merged = mergeAgenda(emptyList(), google)
        assertEquals(listOf("Earlier", "Later"), merged.map { it.label })
        assertTrue(merged.all { it.source == AgendaSource.GOOGLE })
    }

    // ---------------------------------------------------------------------------------- mergeByTime

    @Test
    fun `mergeByTime interleaves local pairs and converted google rows by the paired time`() {
        val local = listOf(3_000L to "Oil change", 1_000L to "Water the plants")
        val google = listOf(googleEvent(1, "Dentist", startMs = 2_000L))

        val merged = mergeByTime(local, google) { it.title }

        assertEquals(listOf("Water the plants", "Dentist", "Oil change"), merged)
    }

    @Test
    fun `mergeByTime with nothing on either side is empty, not a crash`() {
        assertTrue(mergeByTime(emptyList<Pair<Long, String>>(), emptyList()) { it.title }.isEmpty())
    }

    @Test
    fun `mergeAgenda and buildInboxRows share mergeByTime rather than diverging on tie order`() {
        // Both callers hand mergeByTime a list already sorted by its own key, so this only checks
        // that a genuine tie (same millisecond) does not crash or drop a row for either shape -
        // ordering between exact ties is not a contract either caller relies on.
        val local = listOf(AgendaEntry("Local", timeMs = 5_000L, allDay = false))
        val google = listOf(googleEvent(1, "Google", startMs = 5_000L))
        assertEquals(2, mergeAgenda(local, google).size)
    }

    // --------------------------------------------------------------------- buildAgendaCalendarNotice

    @Test
    fun `permission denied always produces a notice, regardless of entry count`() {
        val withEntries = buildAgendaCalendarNotice(calendarPermissionGranted = false, entryCount = 3)
        val withoutEntries = buildAgendaCalendarNotice(calendarPermissionGranted = false, entryCount = 0)

        assertTrue(withEntries.message!!.isNotBlank())
        assertFalse(withEntries.showNothingScheduled)
        assertTrue(withoutEntries.message!!.isNotBlank())
        // The exact failure this guards against: a denied permission must never quietly read as
        // "NOTHING SCHEDULED" just because the local side also happens to be empty.
        assertFalse(withoutEntries.showNothingScheduled)
    }

    @Test
    fun `permission granted with zero entries is the one honest NOTHING SCHEDULED case`() {
        val notice = buildAgendaCalendarNotice(calendarPermissionGranted = true, entryCount = 0)
        assertNull(notice.message)
        assertTrue(notice.showNothingScheduled)
    }

    @Test
    fun `permission granted with entries present shows neither notice nor the empty row`() {
        val notice = buildAgendaCalendarNotice(calendarPermissionGranted = true, entryCount = 4)
        assertNull(notice.message)
        assertFalse(notice.showNothingScheduled)
    }

    // ----------------------------------------------------------- quant-viz ticket 13: WEEK AHEAD

    private val zone: ZoneId = ZoneId.of("America/Chicago")
    private val dayMs = 24L * 60 * 60 * 1000

    /** Seven local day-starts, "today" (arbitrary fixed instant) through six days out. */
    private fun sevenDayStarts(todayMs: Long): List<Long> = dailyBuckets(todayMs, todayMs + 6 * dayMs, zone)

    @Test
    fun `buildWeekAheadDayCounts counts one entry per its own day`() {
        val today = java.time.LocalDate.of(2026, 8, 14).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayStarts = sevenDayStarts(today)
        val entries = listOf(
            AgendaEntry("Dentist", timeMs = today + 3 * 60 * 60 * 1000, allDay = false), // today, 3am local
            AgendaEntry("Oil change", timeMs = dayStarts[2] + 60 * 60 * 1000, allDay = false), // day index 2
        )

        val counts = buildWeekAheadDayCounts(entries, dayStarts, zone)

        assertEquals(listOf(1, 0, 1, 0, 0, 0, 0), counts)
    }

    @Test
    fun `buildWeekAheadDayCounts counts recurring occurrences per occurrence day, not once`() {
        val today = java.time.LocalDate.of(2026, 8, 14).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayStarts = sevenDayStarts(today)
        // A daily recurring reminder produces one occurrence per day - the SAME series appearing
        // on every day of the window must count once PER day, never collapse to one total hit.
        val entries = dayStarts.map { AgendaEntry("Take medication", timeMs = it + 60 * 60 * 1000, allDay = false) }

        val counts = buildWeekAheadDayCounts(entries, dayStarts, zone)

        assertEquals(List(7) { 1 }, counts)
    }

    @Test
    fun `buildWeekAheadDayCounts an empty day is a genuine 0, not a gap`() {
        val today = java.time.LocalDate.of(2026, 8, 14).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayStarts = sevenDayStarts(today)

        val counts = buildWeekAheadDayCounts(emptyList(), dayStarts, zone)

        // Int, never Int? - a week with nothing on it is seven real zeroes.
        assertEquals(List(7) { 0 }, counts)
    }

    @Test
    fun `buildWeekAheadDayCounts an entry outside every bucketed day is silently excluded`() {
        val today = java.time.LocalDate.of(2026, 8, 14).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayStarts = sevenDayStarts(today)
        val entries = listOf(AgendaEntry("Next month", timeMs = today + 40 * dayMs, allDay = false))

        val counts = buildWeekAheadDayCounts(entries, dayStarts, zone)

        assertEquals(List(7) { 0 }, counts)
    }

    @Test
    fun `dayOfWeekLetter returns exactly one character`() {
        val someDay = java.time.LocalDate.of(2026, 8, 14).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(1, dayOfWeekLetter(someDay, zone).length)
    }

    // ------------------------------------------------------------- quant-viz ticket 14: MONTH

    private fun dayStartMs(y: Int, m: Int, d: Int): Long =
        java.time.LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `buildMonthCells cell count is always a multiple of seven`() {
        // August 2026: 1 Aug is a Saturday - a month that starts mid-week, so leading blanks are
        // real (not zero) and exercise the padding math, not just the trailing side.
        val cells = buildMonthCells(java.time.YearMonth.of(2026, 8), emptyMap(), zone)
        assertEquals(0, cells.size % 7)
    }

    @Test
    fun `buildMonthCells leading and trailing slots for a month starting mid-week are blank`() {
        val month = java.time.YearMonth.of(2026, 8) // 1 Aug 2026 is a Saturday
        val cells = buildMonthCells(month, emptyMap(), zone)

        val firstRealDayIndex = cells.indexOfFirst { it.dayOfMonth != null }
        assertTrue("expected at least one leading blank before day 1", firstRealDayIndex > 0)
        (0 until firstRealDayIndex).forEach { i ->
            assertNull(cells[i].dayStart)
            assertNull(cells[i].dayOfMonth)
        }

        val lastRealDayIndex = cells.indexOfLast { it.dayOfMonth != null }
        (lastRealDayIndex + 1 until cells.size).forEach { i ->
            assertNull(cells[i].dayStart)
            assertNull(cells[i].dayOfMonth)
        }
    }

    @Test
    fun `buildMonthCells real days are numbered 1 through the month length in order`() {
        val month = java.time.YearMonth.of(2026, 8)
        val cells = buildMonthCells(month, emptyMap(), zone)
        val realDays = cells.filter { it.dayOfMonth != null }.map { it.dayOfMonth }
        assertEquals((1..month.lengthOfMonth()).toList(), realDays)
    }

    @Test
    fun `buildMonthCells a 31-day month starting near the week boundary spans six week rows`() {
        // August 2026 has 31 days starting on a Saturday - the classic six-row overflow case.
        val month = java.time.YearMonth.of(2026, 8)
        val cells = buildMonthCells(month, emptyMap(), zone)
        assertEquals(42, cells.size) // 6 rows * 7 columns
    }

    @Test
    fun `buildMonthCells counts land on the right day and default to zero elsewhere`() {
        val month = java.time.YearMonth.of(2026, 8)
        val fifth = dayStartMs(2026, 8, 5)
        val counts = mapOf(fifth to 3)

        val cells = buildMonthCells(month, counts, zone)

        val fifthCell = cells.first { it.dayOfMonth == 5 }
        assertEquals(3, fifthCell.eventCount)
        val everyOtherRealCell = cells.filter { it.dayOfMonth != null && it.dayOfMonth != 5 }
        assertTrue(everyOtherRealCell.all { it.eventCount == 0 })
    }

    @Test
    fun `eventDotCount boundaries`() {
        assertEquals(0, eventDotCount(0))
        assertEquals(1, eventDotCount(1))
        assertEquals(1, eventDotCount(2))
        assertEquals(2, eventDotCount(3))
        assertEquals(2, eventDotCount(4))
        assertEquals(3, eventDotCount(5))
        assertEquals(3, eventDotCount(99))
    }

    // -------------------------------------------------------- quant-viz ticket 16: DAY POPUP

    @Test
    fun `entriesForDay returns all-day entries first, then timed ascending`() {
        val day = dayStartMs(2026, 8, 14)
        val entries = listOf(
            AgendaEntry("Late meeting", timeMs = day + 20 * 60 * 60 * 1000, allDay = false),
            AgendaEntry("Birthday", timeMs = day, allDay = true),
            AgendaEntry("Early meeting", timeMs = day + 8 * 60 * 60 * 1000, allDay = false),
        )

        val result = entriesForDay(entries, day, zone)

        assertEquals(listOf("Birthday", "Early meeting", "Late meeting"), result.map { it.label })
    }

    @Test
    fun `entriesForDay is a stable sort, ties keep their incoming relative order`() {
        val day = dayStartMs(2026, 8, 14)
        val entries = listOf(
            AgendaEntry("First all-day", timeMs = day, allDay = true),
            AgendaEntry("Second all-day", timeMs = day, allDay = true),
            AgendaEntry("First timed", timeMs = day + 60 * 60 * 1000, allDay = false),
            AgendaEntry("Second timed", timeMs = day + 60 * 60 * 1000, allDay = false),
        )

        val result = entriesForDay(entries, day, zone)

        assertEquals(listOf("First all-day", "Second all-day", "First timed", "Second timed"), result.map { it.label })
    }

    @Test
    fun `entriesForDay excludes entries on neighbouring days`() {
        val day = dayStartMs(2026, 8, 14)
        val entries = listOf(
            AgendaEntry("Yesterday", timeMs = day - 60 * 60 * 1000, allDay = false),
            AgendaEntry("Today", timeMs = day + 60 * 60 * 1000, allDay = false),
            AgendaEntry("Tomorrow", timeMs = day + dayMs + 60 * 60 * 1000, allDay = false),
        )

        val result = entriesForDay(entries, day, zone)

        assertEquals(listOf("Today"), result.map { it.label })
    }

    @Test
    fun `entriesForDay on an empty day returns empty`() {
        val day = dayStartMs(2026, 8, 14)
        assertTrue(entriesForDay(emptyList(), day, zone).isEmpty())
    }

    @Test
    fun `entriesForDay size equals buildWeekAheadDayCounts for the same day - the dots and the popup can never disagree`() {
        val today = dayStartMs(2026, 8, 14)
        val dayStarts = sevenDayStarts(today)
        // A fixture month's worth of entries scattered across the window, including a tie and an
        // all-day entry, so the invariant is exercised against more than the trivial single-entry case.
        val merged = listOf(
            AgendaEntry("Dentist", timeMs = today + 3 * 60 * 60 * 1000, allDay = false),
            AgendaEntry("Kevin's birthday", timeMs = today, allDay = true),
            AgendaEntry("Oil change", timeMs = dayStarts[2] + 60 * 60 * 1000, allDay = false),
            AgendaEntry("Second oil-change reminder", timeMs = dayStarts[2] + 60 * 60 * 1000, allDay = false),
            AgendaEntry("Out of window", timeMs = today + 40 * dayMs, allDay = false),
        )
        val counts = buildWeekAheadDayCounts(merged, dayStarts, zone)

        dayStarts.forEachIndexed { index, day ->
            assertEquals(
                "day index $index: entriesForDay size must equal the dot-count source count",
                counts[index],
                entriesForDay(merged, day, zone).size,
            )
        }
    }
}
