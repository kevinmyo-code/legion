package com.kevin.legion.dumb

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class DumbStreakTest {
    private val zone = ZoneId.of("America/Chicago")
    private val minute = 60_000L

    private fun at(day: Int, hour: Int, min: Int = 0, month: Int = 10): Long =
        ZonedDateTime.of(2026, month, day, hour, min, 0, 0, zone).toInstant().toEpochMilli()

    private fun run(startDay: Int, startHour: Int, minutes: Long, startMin: Int = 0) =
        DumbInterval(at(startDay, startHour, startMin), at(startDay, startHour, startMin) + minutes * minute)

    private fun streak(history: List<DumbInterval>, now: Long, current: DumbModeState = DumbModeState()) =
        DumbStreaks.compute(history, current, now, zone)

    @Test
    fun `no history is no streak`() {
        assertEquals(DumbStreak(0, 0L), streak(emptyList(), at(22, 12)))
    }

    @Test
    fun `a day needs a full 60 minutes and 59 is not enough`() {
        assertEquals(1, streak(listOf(run(22, 9, 60)), at(22, 12)).days)
        assertEquals(0, streak(listOf(run(22, 9, 59)), at(22, 12)).days)
    }

    @Test
    fun `several short runs on one day add up`() {
        val runs = listOf(run(22, 8, 20), run(22, 12, 20), run(22, 18, 20))
        assertEquals(1, streak(runs, at(22, 20)).days)
    }

    @Test
    fun `consecutive days make a streak and a missed day breaks it`() {
        val runs = listOf(run(19, 9, 90), run(20, 9, 90), run(21, 9, 90), run(22, 9, 90))
        assertEquals(4, streak(runs, at(22, 15)).days)
        val gap = listOf(run(18, 9, 90), run(20, 9, 90), run(21, 9, 90), run(22, 9, 90))
        assertEquals(3, streak(gap, at(22, 15)).days)
    }

    @Test
    fun `today not yet at an hour does not break yesterday's streak`() {
        val runs = listOf(run(20, 9, 90), run(21, 9, 90), run(22, 9, 30))
        assertEquals(2, streak(runs, at(22, 10)).days)
        assertEquals(30 * minute, streak(runs, at(22, 10)).todayMs)
    }

    @Test
    fun `a day with nothing since two days ago is zero`() {
        assertEquals(0, streak(listOf(run(20, 9, 90)), at(22, 12)).days)
    }

    @Test
    fun `a run across midnight is split between the two days`() {
        // 23:00 to 01:30: 60 minutes on the 21st, 90 on the 22nd. Both qualify.
        val overnight = DumbInterval(at(21, 23), at(22, 1, 30))
        val result = streak(listOf(overnight), at(22, 12))
        assertEquals(2, result.days)
        assertEquals(90 * minute, result.todayMs)
    }

    @Test
    fun `a run across midnight that is short on one side only credits the other`() {
        // 23:30 to 02:00: 30 minutes on the 21st (not enough), 120 on the 22nd.
        val overnight = DumbInterval(at(21, 23, 30), at(22, 2))
        assertEquals(1, streak(listOf(overnight), at(22, 12)).days)
    }

    @Test
    fun `the running session counts toward today`() {
        val current = DumbModeState(on = true, startedAtMs = at(22, 9))
        val result = streak(listOf(run(21, 9, 90)), at(22, 10, 30), current)
        assertEquals(2, result.days)
        assertEquals(90 * minute, result.todayMs)
    }

    @Test
    fun `a running session of 59 minutes does not yet count`() {
        val current = DumbModeState(on = true, startedAtMs = at(22, 9))
        assertEquals(1, streak(listOf(run(21, 9, 90)), at(22, 9, 59), current).days)
    }

    @Test
    fun `a session that has run for days credits every day it covered`() {
        val current = DumbModeState(on = true, startedAtMs = at(19, 10))
        assertEquals(4, streak(emptyList(), at(22, 15), current).days)
    }

    @Test
    fun `the spring forward day is still a day`() {
        // 2026-03-08 is the US spring-forward (23 hour day in Chicago). Two hours on it still qualifies.
        val before = DumbInterval(
            ZonedDateTime.of(2026, 3, 7, 9, 0, 0, 0, zone).toInstant().toEpochMilli(),
            ZonedDateTime.of(2026, 3, 7, 11, 0, 0, 0, zone).toInstant().toEpochMilli(),
        )
        val on = DumbInterval(
            ZonedDateTime.of(2026, 3, 8, 9, 0, 0, 0, zone).toInstant().toEpochMilli(),
            ZonedDateTime.of(2026, 3, 8, 11, 0, 0, 0, zone).toInstant().toEpochMilli(),
        )
        val now = ZonedDateTime.of(2026, 3, 8, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(2, streak(listOf(before, on), now).days)
    }

    @Test
    fun `the status line says the streak only when there is one`() {
        assertEquals("dumb for 2h 14m", DumbFormat.statusLine("2h 14m", 0))
        assertEquals("dumb for 2h 14m · 1 day in a row", DumbFormat.statusLine("2h 14m", 1))
        assertEquals("dumb for 2h 14m · 4 days in a row", DumbFormat.statusLine("2h 14m", 4))
    }
}
