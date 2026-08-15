package com.kevin.legion.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Exhaustive coverage of the pure occurrence generator (`.scratch/notes-lists-calendar/issues/04-
 * recurrence-model.md` calls this "the highest-risk correctness surface" on the map). No `Context`,
 * no Room - plain JVM.
 */
class RecurrenceTest {

    private fun date(iso: String): Long = LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private fun dateOf(epoch: Long): LocalDate = Instant.ofEpochMilli(epoch).atZone(ZoneOffset.UTC).toLocalDate()
    private fun dates(occurrences: List<Long>): List<String> = occurrences.map { dateOf(it).toString() }

    // ------------------------------------------------------------------------------------ Daily

    @Test
    fun `daily every 1 day fires every day in the window`() {
        val occ = Recurrence.occurrencesInWindow(
            startsAt = date("2026-08-01"),
            rule = RepeatRule.Daily(1),
            end = RepeatEnd.Never,
            skippedDates = emptySet(),
            windowStart = date("2026-08-01"),
            windowEnd = date("2026-08-05"),
                      zone = ZoneOffset.UTC,
                  )
        assertEquals(listOf("2026-08-01", "2026-08-02", "2026-08-03", "2026-08-04", "2026-08-05"), dates(occ))
    }

    @Test
    fun `daily every 3 days skips the ones in between`() {
        val occ = Recurrence.occurrencesInWindow(
            startsAt = date("2026-08-01"),
            rule = RepeatRule.Daily(3),
            end = RepeatEnd.Never,
            skippedDates = emptySet(),
            windowStart = date("2026-08-01"),
            windowEnd = date("2026-08-10"),
                      zone = ZoneOffset.UTC,
                  )
        assertEquals(listOf("2026-08-01", "2026-08-04", "2026-08-07", "2026-08-10"), dates(occ))
    }

    @Test
    fun `daily never yields a date before the series start`() {
        val occ = Recurrence.occurrencesInWindow(
            startsAt = date("2026-08-05"),
            rule = RepeatRule.Daily(1),
            end = RepeatEnd.Never,
            skippedDates = emptySet(),
            windowStart = date("2026-08-01"),
            windowEnd = date("2026-08-05"),
                      zone = ZoneOffset.UTC,
                  )
        assertEquals(listOf("2026-08-05"), dates(occ))
    }

    // ----------------------------------------------------------------------------------- Weekly

    @Test
    fun `weekly every 1 week fires on each chosen weekday`() {
        // 2026-08-03 is a Monday.
        val occ = Recurrence.occurrencesInWindow(
            startsAt = date("2026-08-03"),
            rule = RepeatRule.Weekly(1, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)),
            end = RepeatEnd.Never,
            skippedDates = emptySet(),
            windowStart = date("2026-08-03"),
            windowEnd = date("2026-08-14"),
                      zone = ZoneOffset.UTC,
                  )
        assertEquals(
            listOf("2026-08-03", "2026-08-05", "2026-08-07", "2026-08-10", "2026-08-12", "2026-08-14"),
            dates(occ),
        )
    }

    @Test
    fun `weekly every 2 weeks skips the alternating week entirely`() {
        val occ = Recurrence.occurrencesInWindow(
            startsAt = date("2026-08-03"), // Monday
            rule = RepeatRule.Weekly(2, setOf(DayOfWeek.MONDAY)),
            end = RepeatEnd.Never,
            skippedDates = emptySet(),
            windowStart = date("2026-08-03"),
            windowEnd = date("2026-09-14"),
                      zone = ZoneOffset.UTC,
                  )
        assertEquals(listOf("2026-08-03", "2026-08-17", "2026-08-31", "2026-09-14"), dates(occ))
    }

    @Test
    fun `weekly excludes a day-of-week date earlier in the start week than the start date`() {
        // Start on Wednesday, days include Monday - the Monday of the FIRST week is before the
        // series began, so it must not appear; the next Monday (week 2) must.
        val occ = Recurrence.occurrencesInWindow(
            startsAt = date("2026-08-05"), // Wednesday
            rule = RepeatRule.Weekly(1, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)),
            end = RepeatEnd.Never,
            skippedDates = emptySet(),
            windowStart = date("2026-08-01"),
            windowEnd = date("2026-08-12"),
                      zone = ZoneOffset.UTC,
                  )
        assertEquals(listOf("2026-08-05", "2026-08-10", "2026-08-12"), dates(occ))
    }

    @Test
    fun `weekly with an empty day set yields nothing rather than hanging`() {
        val occ = Recurrence.occurrencesInWindow(
            startsAt = date("2026-08-03"),
            rule = RepeatRule.Weekly(1, emptySet()),
            end = RepeatEnd.Never,
            skippedDates = emptySet(),
            windowStart = date("2026-08-01"),
            windowEnd = date("2026-12-31"),
                      zone = ZoneOffset.UTC,
                  )
        assertTrue(occ.isEmpty())
    }

    // -------------------------------------------------------------------------- MonthlyOnDate

    @Test
    fun `monthly on date fires the same day-of-month each interval`() {
        val occ = Recurrence.occurrencesInWindow(
            startsAt = date("2026-08-15"),
            rule = RepeatRule.MonthlyOnDate(1, 15),
            end = RepeatEnd.Never,
            skippedDates = emptySet(),
            windowStart = date("2026-08-01"),
            windowEnd = date("2026-11-01"),
                      zone = ZoneOffset.UTC,
                  )
        assertEquals(listOf("2026-08-15", "2026-09-15", "2026-10-15"), dates(occ))
    }

    @Test
    fun `monthly on date 31 in a 30-day month fires on that month's last day, not next month's 1st`() {
        // Ticket 04's named edge case, verbatim.
        val occ = Recurrence.occurrencesInWindow(
            startsAt = date("2026-03-31"),
            rule = RepeatRule.MonthlyOnDate(1, 31),
            end = RepeatEnd.Never,
            skippedDates = emptySet(),
            windowStart = date("2026-03-01"),
            windowEnd = date("2026-06-30"),
                      zone = ZoneOffset.UTC,
                  )
        // Mar 31, Apr 30 (clamped, NOT May 1), May 31, Jun 30 (clamped).
        assertEquals(listOf("2026-03-31", "2026-04-30", "2026-05-31", "2026-06-30"), dates(occ))
    }

    @Test
    fun `monthly every 2 months skips the interleaving month`() {
        val occ = Recurrence.occurrencesInWindow(
            startsAt = date("2026-01-10"),
            rule = RepeatRule.MonthlyOnDate(2, 10),
            end = RepeatEnd.Never,
            skippedDates = emptySet(),
            windowStart = date("2026-01-01"),
            windowEnd = date("2026-05-31"),
                      zone = ZoneOffset.UTC,
                  )
        assertEquals(listOf("2026-01-10", "2026-03-10", "2026-05-10"), dates(occ))
    }

    // -------------------------------------------------------------------------------- Yearly

    @Test
    fun `yearly fires once a year on the same month and day`() {
        val occ = Recurrence.occurrencesInWindow(
            startsAt = date("2024-06-15"),
            rule = RepeatRule.Yearly(6, 15),
            end = RepeatEnd.Never,
            skippedDates = emptySet(),
            windowStart = date("2024-01-01"),
            windowEnd = date("2027-01-01"),
                      zone = ZoneOffset.UTC,
                  )
        assertEquals(listOf("2024-06-15", "2025-06-15", "2026-06-15"), dates(occ))
    }

    @Test
    fun `yearly Feb 29 in a common year fires on Feb 28`() {
        // Ticket 04's other named edge case, verbatim. 2024 is a leap year, 2025/2026 are not.
        val occ = Recurrence.occurrencesInWindow(
            startsAt = date("2024-02-29"),
            rule = RepeatRule.Yearly(2, 29),
            end = RepeatEnd.Never,
            skippedDates = emptySet(),
            windowStart = date("2024-01-01"),
            windowEnd = date("2026-12-31"),
                      zone = ZoneOffset.UTC,
                  )
        assertEquals(listOf("2024-02-29", "2025-02-28", "2026-02-28"), dates(occ))
    }

    // ------------------------------------------------------------------------------ end kinds

    @Test
    fun `end Never keeps generating up to the window bound`() {
        val occ = Recurrence.occurrencesInWindow(
            startsAt = date("2026-01-01"),
            rule = RepeatRule.Daily(1),
            end = RepeatEnd.Never,
            skippedDates = emptySet(),
            windowStart = date("2026-01-01"),
            windowEnd = date("2026-01-03"),
                      zone = ZoneOffset.UTC,
                  )
        assertEquals(3, occ.size)
    }

    @Test
    fun `end OnDate stops generating after the cutoff date, inclusive`() {
        val occ = Recurrence.occurrencesInWindow(
            startsAt = date("2026-01-01"),
            rule = RepeatRule.Daily(1),
            end = RepeatEnd.OnDate(date("2026-01-05")),
            skippedDates = emptySet(),
            windowStart = date("2026-01-01"),
            windowEnd = date("2026-01-31"),
                      zone = ZoneOffset.UTC,
                  )
        assertEquals(listOf("2026-01-01", "2026-01-02", "2026-01-03", "2026-01-04", "2026-01-05"), dates(occ))
    }

    @Test
    fun `end AfterCount stops after exactly n occurrences regardless of the query window`() {
        val occ = Recurrence.occurrencesInWindow(
            startsAt = date("2026-01-01"),
            rule = RepeatRule.Daily(1),
            end = RepeatEnd.AfterCount(3),
            skippedDates = emptySet(),
            windowStart = date("2026-01-01"),
            windowEnd = date("2026-12-31"),
                      zone = ZoneOffset.UTC,
                  )
        assertEquals(listOf("2026-01-01", "2026-01-02", "2026-01-03"), dates(occ))
    }

    @Test
    fun `end AfterCount with a window entirely after the series ends returns nothing`() {
        val occ = Recurrence.occurrencesInWindow(
            startsAt = date("2026-01-01"),
            rule = RepeatRule.Daily(1),
            end = RepeatEnd.AfterCount(3),
            skippedDates = emptySet(),
            windowStart = date("2026-06-01"),
            windowEnd = date("2026-06-30"),
                      zone = ZoneOffset.UTC,
                  )
        assertTrue(occ.isEmpty())
    }

    // ---------------------------------------------------------------------------------- skips

    @Test
    fun `a skipped date is subtracted during expansion, not present in the result`() {
        val occ = Recurrence.occurrencesInWindow(
            startsAt = date("2026-08-01"),
            rule = RepeatRule.Daily(1),
            end = RepeatEnd.Never,
            skippedDates = setOf(date("2026-08-03")),
            windowStart = date("2026-08-01"),
            windowEnd = date("2026-08-05"),
                      zone = ZoneOffset.UTC,
                  )
        assertEquals(listOf("2026-08-01", "2026-08-02", "2026-08-04", "2026-08-05"), dates(occ))
    }

    @Test
    fun `a skipped occurrence still counts toward an AfterCount limit`() {
        // Skipping does not extend a series past the number of times it was asked to occur -
        // ticket 04/Recurrence's own doc comment. 5 occurrences total, one skipped, so only 4
        // dates come back, not 5.
        val occ = Recurrence.occurrencesInWindow(
            startsAt = date("2026-08-01"),
            rule = RepeatRule.Daily(1),
            end = RepeatEnd.AfterCount(5),
            skippedDates = setOf(date("2026-08-03")),
            windowStart = date("2026-08-01"),
            windowEnd = date("2026-12-31"),
                      zone = ZoneOffset.UTC,
                  )
        assertEquals(listOf("2026-08-01", "2026-08-02", "2026-08-04", "2026-08-05"), dates(occ))
        assertEquals(4, occ.size)
    }

    // ------------------------------------------------------------------------------- misc guards

    @Test
    fun `an every of 0 or less yields nothing rather than looping forever`() {
        val occ = Recurrence.occurrencesInWindow(
            startsAt = date("2026-08-01"),
            rule = RepeatRule.Daily(0),
            end = RepeatEnd.Never,
            skippedDates = emptySet(),
            windowStart = date("2026-08-01"),
            windowEnd = date("2026-08-31"),
                      zone = ZoneOffset.UTC,
                  )
        assertTrue(occ.isEmpty())
    }

    @Test
    fun `a time of day on startsAt is preserved on every later occurrence`() {
        val startsAt = date("2026-08-01") + 18 * 60 * 60 * 1000L // 6pm UTC
        val occ = Recurrence.occurrencesInWindow(
            startsAt = startsAt,
            rule = RepeatRule.Daily(1),
            end = RepeatEnd.Never,
            skippedDates = emptySet(),
            windowStart = date("2026-08-01"),
            windowEnd = date("2026-08-03"),
                      zone = ZoneOffset.UTC,
                  )
        for (o in occ) {
            assertEquals(18L, (o - dateOf(o).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()) / (60 * 60 * 1000L))
        }
    }
}
