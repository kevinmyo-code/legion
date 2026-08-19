package com.kevin.legion.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Coverage for [NextOccurrence] - ticket 03/04's "boot recovery must recompute the next occurrence
 * forward from now, never resume from the last fired occurrence". No `Context`, no Room - plain JVM.
 */
class NextOccurrenceTest {

    private fun date(iso: String): Long = LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private fun dateOf(epoch: Long): LocalDate = Instant.ofEpochMilli(epoch).atZone(ZoneOffset.UTC).toLocalDate()

    @Test
    fun `daily series - next occurrence at or after now is tomorrow when today's already passed`() {
        val next = NextOccurrence.compute(
            startsAt = date("2026-08-01"),
            rule = RepeatRule.Daily(1),
            end = RepeatEnd.Never,
            skippedDates = emptySet(),
            now = date("2026-08-10") + 1, // just after today's UTC-midnight occurrence,
                       zone = ZoneOffset.UTC,
                   )
        assertEquals(LocalDate.parse("2026-08-11"), next?.let { dateOf(it) })
    }

    @Test
    fun `daily series - now exactly on an occurrence's instant returns that same instant`() {
        val occurrence = date("2026-08-10")
        val next = NextOccurrence.compute(
            startsAt = date("2026-08-01"), rule = RepeatRule.Daily(1), end = RepeatEnd.Never,
            skippedDates = emptySet(), now = occurrence,
                       zone = ZoneOffset.UTC,
                   )
        assertEquals(occurrence, next)
    }

    @Test
    fun `weekly series - the phone being off for a week does not create a backlog, only the next Monday`() {
        // A weekly-Monday series that last fired two Mondays ago - simulating a phone that was off
        // the whole time. The correct next alarm is the UPCOMING Monday, not either missed one.
        val next = NextOccurrence.compute(
            startsAt = date("2026-08-03"), // a Monday
            rule = RepeatRule.Weekly(1, setOf(DayOfWeek.MONDAY)),
            end = RepeatEnd.Never,
            skippedDates = emptySet(),
            now = date("2026-08-18") + 1, // Tuesday, one day after the second Monday it missed,
                       zone = ZoneOffset.UTC,
                   )
        assertEquals(LocalDate.parse("2026-08-24"), next?.let { dateOf(it) }) // the NEXT Monday, not a backlog
    }

    @Test
    fun `yearly series - found within the widened window even though it's nearly a year out`() {
        val next = NextOccurrence.compute(
            startsAt = date("2020-03-15"),
            rule = RepeatRule.Yearly(3, 15),
            end = RepeatEnd.Never,
            skippedDates = emptySet(),
            now = date("2026-03-16"), // one day after this year's occurrence already passed,
                       zone = ZoneOffset.UTC,
                   )
        assertEquals(LocalDate.parse("2027-03-15"), next?.let { dateOf(it) })
    }

    @Test
    fun `a skipped next occurrence is passed over for the one after it`() {
        val next = NextOccurrence.compute(
            startsAt = date("2026-08-01"),
            rule = RepeatRule.Daily(1),
            end = RepeatEnd.Never,
            skippedDates = setOf(date("2026-08-10")),
            now = date("2026-08-10"),
                       zone = ZoneOffset.UTC,
                   )
        assertEquals(LocalDate.parse("2026-08-11"), next?.let { dateOf(it) })
    }

    @Test
    fun `a series already ended by date returns null, never a stale past occurrence`() {
        val next = NextOccurrence.compute(
            startsAt = date("2026-08-01"),
            rule = RepeatRule.Daily(1),
            end = RepeatEnd.OnDate(date("2026-08-05")),
            skippedDates = emptySet(),
            now = date("2026-08-10"),
                       zone = ZoneOffset.UTC,
                   )
        assertNull(next)
    }

    @Test
    fun `a series exhausted by count returns null once every occurrence has already happened`() {
        val next = NextOccurrence.compute(
            startsAt = date("2026-08-01"),
            rule = RepeatRule.Daily(1),
            end = RepeatEnd.AfterCount(3),
            skippedDates = emptySet(),
            now = date("2026-08-10"), // well past all 3 of Aug 1/2/3,
                       zone = ZoneOffset.UTC,
                   )
        assertNull(next)
    }

    @Test
    fun `a malformed rule (every less than 1) never advances and returns null`() {
        val next = NextOccurrence.compute(
            startsAt = date("2026-08-01"), rule = RepeatRule.Daily(0), end = RepeatEnd.Never,
            skippedDates = emptySet(), now = date("2026-08-10"),
                       zone = ZoneOffset.UTC,
                   )
        assertNull(next)
    }
}
