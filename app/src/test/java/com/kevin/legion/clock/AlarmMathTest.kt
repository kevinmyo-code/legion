package com.kevin.legion.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The clock's arithmetic (alfred-clock ticket 01): repeat days, one-offs, DST, zone change, durations. */
class AlarmMathTest {
    private val chicago = ZoneId.of("America/Chicago")
    private val newYork = ZoneId.of("America/New_York")

    private fun ms(iso: String, zone: ZoneId = chicago) =
        ZonedDateTime.of(LocalDateTime.parse(iso), zone).toInstant().toEpochMilli()

    private fun alarm(h: Int, m: Int, days: Set<DayOfWeek> = emptySet(), date: LocalDate? = null) =
        ClockAlarm(id = 1, hour = h, minute = m, days = days, date = date)

    @Test
    fun `weekday alarm set on Friday night rings Monday morning`() {
        // 2026-11-13 is a Friday.
        val at = AlarmMath.nextTrigger(alarm(7, 0, AlarmMath.WEEKDAYS), ms("2026-11-13T22:00"), chicago)
        assertEquals(ms("2026-11-16T07:00"), at)
    }

    @Test
    fun `a repeating alarm later today rings today, one already past rings on its next day`() {
        val a = alarm(7, 0, AlarmMath.EVERY_DAY)
        assertEquals(ms("2026-11-10T07:00"), AlarmMath.nextTrigger(a, ms("2026-11-10T06:59"), chicago))
        assertEquals(ms("2026-11-11T07:00"), AlarmMath.nextTrigger(a, ms("2026-11-10T07:00"), chicago))
    }

    @Test
    fun `a one-off is today while its time is ahead, else tomorrow`() {
        assertEquals(LocalDate.parse("2026-11-09"), AlarmMath.oneOffDate(23, 0, ms("2026-11-09T22:00"), chicago))
        assertEquals(LocalDate.parse("2026-11-10"), AlarmMath.oneOffDate(7, 0, ms("2026-11-09T22:00"), chicago))
    }

    @Test
    fun `an alarm that is off has no trigger`() {
        val off = alarm(7, 0, AlarmMath.EVERY_DAY).copy(enabled = false)
        assertNull(AlarmMath.nextTrigger(off, ms("2026-11-09T22:00"), chicago))
    }

    @Test
    fun `a pending snooze wins over the time of day`() {
        val now = ms("2026-11-10T07:00")
        val snoozed = alarm(7, 0, AlarmMath.EVERY_DAY).copy(snoozedUntilMs = now + 9 * 60_000)
        assertEquals(now + 9 * 60_000, AlarmMath.nextTrigger(snoozed, now, chicago))
        // Once the snooze is in the past it no longer counts.
        assertEquals(ms("2026-11-11T07:00"), AlarmMath.nextTrigger(snoozed, now + 10 * 60_000, chicago))
    }

    @Test
    fun `spring forward - a time inside the gap moves later by the gap`() {
        // 2026-03-08 02:00 New York jumps to 03:00; 02:30 does not exist.
        val at = AlarmMath.nextTrigger(alarm(2, 30, AlarmMath.EVERY_DAY), ms("2026-03-08T00:00", newYork), newYork)
        assertEquals(ZonedDateTime.of(LocalDateTime.parse("2026-03-08T03:30"), newYork).toInstant().toEpochMilli(), at)
    }

    @Test
    fun `fall back - a time inside the overlap rings once, at the earlier offset`() {
        // 2026-11-01 01:30 New York happens twice; the first is EDT (-4).
        val at = AlarmMath.nextTrigger(alarm(1, 30, AlarmMath.EVERY_DAY), ms("2026-11-01T00:00", newYork), newYork)
        assertEquals(java.time.OffsetDateTime.parse("2026-11-01T01:30-04:00").toInstant().toEpochMilli(), at)
        val next = AlarmMath.nextTrigger(alarm(1, 30, AlarmMath.EVERY_DAY), at!!, newYork)
        assertEquals(ms("2026-11-02T01:30", newYork), next)
    }

    @Test
    fun `a 7 am alarm across a DST change is still 7 am local`() {
        // Saturday night before fall-back in Chicago: Sunday 07:00 CST is 13:00 UTC, not 12:00.
        val at = AlarmMath.nextTrigger(alarm(7, 0, AlarmMath.EVERY_DAY), ms("2026-10-31T22:00"), chicago)
        assertEquals(java.time.Instant.parse("2026-11-01T13:00:00Z").toEpochMilli(), at)
    }

    @Test
    fun `a zone change moves the instant, never the 7 00`() {
        val oneOff = alarm(7, 0, date = LocalDate.parse("2026-11-10"))
        val inChicago = AlarmMath.nextTrigger(oneOff, ms("2026-11-09T22:00"), chicago)!!
        val inNewYork = AlarmMath.nextTrigger(oneOff, ms("2026-11-09T22:00"), newYork)!!
        assertEquals(60 * 60_000L, inChicago - inNewYork)
    }

    @Test
    fun `times are read the way the model writes them`() {
        assertEquals(7 to 0, AlarmMath.parseTime("07:00"))
        assertEquals(7 to 30, AlarmMath.parseTime("7:30"))
        assertEquals(7 to 0, AlarmMath.parseTime("7"))
        assertEquals(18 to 45, AlarmMath.parseTime("6:45 pm"))
        assertEquals(0 to 15, AlarmMath.parseTime("12:15am"))
        assertEquals(12 to 0, AlarmMath.parseTime("12pm"))
        assertEquals(18 to 30, AlarmMath.parseTime("1830"))
        assertNull(AlarmMath.parseTime("25:00"))
        assertNull(AlarmMath.parseTime("7:61"))
        assertNull(AlarmMath.parseTime("tomorrow"))
    }

    @Test
    fun `days are read in words and as lists`() {
        assertEquals(emptySet<DayOfWeek>(), AlarmMath.parseDays(""))
        assertEquals(emptySet<DayOfWeek>(), AlarmMath.parseDays(null))
        assertEquals(AlarmMath.WEEKDAYS, AlarmMath.parseDays("weekdays"))
        assertEquals(AlarmMath.WEEKEND, AlarmMath.parseDays("Weekends"))
        assertEquals(AlarmMath.EVERY_DAY, AlarmMath.parseDays("every day"))
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), AlarmMath.parseDays("mon,fri"))
        assertEquals(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), AlarmMath.parseDays("Tuesday, Thursday"))
        assertNull(AlarmMath.parseDays("mon,someday"))
        assertEquals("weekdays", AlarmMath.daysLabel(AlarmMath.WEEKDAYS))
        assertEquals("Mon, Fri", AlarmMath.daysLabel(setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY)))
    }

    @Test
    fun `a timer counts on elapsed time within its boot and on the wall clock after a reboot`() {
        val t = ClockTimer(
            1, "pasta", 600_000, TimerState.RUNNING, 600_000,
            endsAtElapsed = 700_000, endsAtWallMs = 2_000_000, bootCount = 3,
        )
        assertEquals(200_000L, AlarmMath.timerRemaining(t, nowElapsed = 500_000, nowWallMs = 0, bootCount = 3))
        assertEquals(500_000L, AlarmMath.timerRemaining(t, nowElapsed = 10, nowWallMs = 1_500_000, bootCount = 4))
        assertEquals(0L, AlarmMath.timerRemaining(t, nowElapsed = 900_000, nowWallMs = 0, bootCount = 3))
    }

    @Test
    fun `durations are said in words and shown as digits`() {
        assertEquals("4 minutes 12 seconds", AlarmMath.spokenDuration(252_000))
        assertEquals("1 hour 5 minutes", AlarmMath.spokenDuration(3_909_000))
        assertEquals("1 second", AlarmMath.spokenDuration(400))
        assertEquals("0 seconds", AlarmMath.spokenDuration(0))
        assertEquals("4:12", AlarmMath.digital(252_000))
        assertEquals("1:05:09", AlarmMath.digital(3_909_000))
    }
}
