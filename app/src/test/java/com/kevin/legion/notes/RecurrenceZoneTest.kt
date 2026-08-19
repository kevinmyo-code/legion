package com.kevin.legion.notes

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Regression tests for the 2026-08-07 audit finding: [Recurrence] did all of its
 * day-maths in UTC while `ListItem.startsAt` is written as a **device-zone**
 * instant.
 *
 * **`RecurrenceTest` could not have caught this.** Every fixture in it is built
 * with `atStartOfDay(ZoneOffset.UTC)`, so the suite never left UTC and a
 * UTC-versus-device-zone mismatch was invisible by construction. That is the
 * point worth remembering: the tests were not weak, they were *self-consistent*,
 * which is a different thing and a much harder one to notice.
 *
 * Everything here therefore builds its fixtures the way the real app does - a
 * local wall-clock time resolved in a named zone - and asserts on the local
 * wall-clock day the driver actually experiences.
 */
class RecurrenceZoneTest {

    private val tokyo = ZoneId.of("Asia/Tokyo")           // UTC+9, no DST
    private val losAngeles = ZoneId.of("America/Los_Angeles")
    private val chicago = ZoneId.of("America/Chicago")     // Kevin's own zone

    /** An instant, built the way `ListDetailScreen` and the voice path build one. */
    private fun localInstant(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
        LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()

    private fun localDayOf(epochMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    private fun localTimeOf(epochMillis: Long, zone: ZoneId): LocalTime =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime()

    @Test
    fun `weekly Monday stays on Monday east of UTC`() {
        // The traced failure: 00:30 JST on Monday is 15:30Z the previous SUNDAY,
        // so the UTC-anchored day-of-week read Sunday and the whole series
        // landed on Tuesdays, permanently.
        val start = localInstant(LocalDate.of(2026, 8, 10), LocalTime.of(0, 30), tokyo)
        val occurrences = Recurrence.occurrencesInWindow(
            startsAt = start,
            rule = RepeatRule.Weekly(every = 1, days = setOf(DayOfWeek.MONDAY)),
            end = RepeatEnd.Never,
            skippedDates = emptySet(),
            windowStart = start,
            windowEnd = start + 30L * 24 * 3600 * 1000,
            zone = tokyo,
        )

        assertEquals("Mondays in a 30-day window from Mon 10 Aug: 10/17/24/31 Aug + 7 Sep", 5, occurrences.size)
        occurrences.forEach {
            assertEquals(DayOfWeek.MONDAY, localDayOf(it, tokyo).dayOfWeek)
            assertEquals("time of day must survive every occurrence", LocalTime.of(0, 30), localTimeOf(it, tokyo))
        }
    }

    @Test
    fun `weekly Monday stays on Monday west of UTC`() {
        // The mirror case: 23:30 PDT on Monday is already Tuesday in UTC, which
        // shifted the series onto Sundays.
        val start = localInstant(LocalDate.of(2026, 8, 10), LocalTime.of(23, 30), losAngeles)
        val occurrences = Recurrence.occurrencesInWindow(
            startsAt = start,
            rule = RepeatRule.Weekly(every = 1, days = setOf(DayOfWeek.MONDAY)),
            end = RepeatEnd.Never,
            skippedDates = emptySet(),
            windowStart = start,
            windowEnd = start + 30L * 24 * 3600 * 1000,
            zone = losAngeles,
        )

        assertEquals(5, occurrences.size)
        occurrences.forEach { assertEquals(DayOfWeek.MONDAY, localDayOf(it, losAngeles).dayOfWeek) }
    }

    @Test
    fun `monthly on the 1st stays on the 1st east of UTC`() {
        val start = localInstant(LocalDate.of(2026, 9, 1), LocalTime.of(0, 30), tokyo)
        val occurrences = Recurrence.occurrencesInWindow(
            startsAt = start,
            rule = RepeatRule.MonthlyOnDate(every = 1, day = 1),
            end = RepeatEnd.AfterCount(3),
            skippedDates = emptySet(),
            windowStart = start,
            windowEnd = start + 120L * 24 * 3600 * 1000,
            zone = tokyo,
        )

        assertEquals(3, occurrences.size)
        occurrences.forEach { assertEquals(1, localDayOf(it, tokyo).dayOfMonth) }
    }

    @Test
    fun `a daily 7am reminder stays at 7am across a DST transition`() {
        // America/Chicago springs forward on 2026-03-08. A UTC-anchored
        // generator produces occurrences exactly 24h apart in ABSOLUTE time, so
        // a 7am reminder silently becomes 8am (or 6am) after the transition.
        // Anchoring the day-maths in the zone keeps the wall-clock hour.
        val start = localInstant(LocalDate.of(2026, 3, 6), LocalTime.of(7, 0), chicago)
        val occurrences = Recurrence.occurrencesInWindow(
            startsAt = start,
            rule = RepeatRule.Daily(every = 1),
            end = RepeatEnd.AfterCount(6),
            skippedDates = emptySet(),
            windowStart = start,
            windowEnd = start + 10L * 24 * 3600 * 1000,
            zone = chicago,
        )

        assertEquals(6, occurrences.size)
        occurrences.forEach {
            assertEquals(
                "7am must stay 7am local across the spring-forward boundary",
                LocalTime.of(7, 0),
                localTimeOf(it, chicago),
            )
        }
    }

    @Test
    fun `the same series read in UTC and in a device zone disagree - which is the bug`() {
        // Kept deliberately as documentation of WHY the zone is a parameter: the
        // two readings of one stored instant genuinely differ, so a caller that
        // omits the zone is not making a harmless choice.
        val start = localInstant(LocalDate.of(2026, 8, 10), LocalTime.of(0, 30), tokyo)
        val args = arrayOf(
            RepeatRule.Weekly(every = 1, days = setOf(DayOfWeek.MONDAY)),
            RepeatEnd.Never,
        )

        val inTokyo = Recurrence.occurrencesInWindow(
            start, args[0] as RepeatRule, args[1] as RepeatEnd, emptySet(),
            start, start + 14L * 24 * 3600 * 1000, tokyo,
        )
        val inUtc = Recurrence.occurrencesInWindow(
            start, args[0] as RepeatRule, args[1] as RepeatEnd, emptySet(),
            start, start + 14L * 24 * 3600 * 1000, ZoneOffset.UTC,
        )

        assertEquals(DayOfWeek.MONDAY, localDayOf(inTokyo.first(), tokyo).dayOfWeek)
        assertEquals(
            "the UTC reading lands on a Tuesday in Tokyo - this is exactly what shipped",
            DayOfWeek.TUESDAY,
            localDayOf(inUtc.first(), tokyo).dayOfWeek,
        )
    }
}
