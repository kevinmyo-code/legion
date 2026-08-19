package com.kevin.legion.workouts

import com.kevin.legion.data.local.WorkoutSetLog
import com.kevin.legion.plan.TrustTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Exercises [buildWeeklyWorkoutGap] (D24), plain JUnit - pure by construction, same posture as
 * [com.kevin.legion.ledger.LedgerBudgetTest]. All fixtures are invented.
 */
class WorkoutGapTest {

    private var nextId = 1L

    private fun setLog(exercise: String, sets: Int, loggedAt: Long) = WorkoutSetLog(
        id = nextId++, exercise = exercise, sets = sets, loggedAt = loggedAt, trustTier = TrustTier.REPORTED,
    )

    /** Explicit, so the suite is deterministic on any machine - see [weekStartEpoch]'s doc comment. */
    private val zone = ZoneId.of("America/Chicago")

    private val monday = weekStartEpoch(1_733_356_800_000L, zone) // an arbitrary Wednesday, snapped to its Monday
    private val tuesday = monday + 24L * 60 * 60 * 1000
    private val wednesday = monday + 2L * 24 * 60 * 60 * 1000

    @Test
    fun `a session is a distinct day, not a distinct exercise - two exercises same day is one session`() {
        val logs = listOf(
            setLog("Squat", 3, monday),
            setLog("Bench Press", 3, monday), // same day, different exercise
        )
        val gap = buildWeeklyWorkoutGap(sessionsPlanned = 3, setsThisWeek = logs, zone = zone)
        assertEquals(1, gap.actual)
        assertEquals(2, gap.gap)
    }

    @Test
    fun `multiple sets on different days each count toward sessions done`() {
        val logs = listOf(setLog("Squat", 3, monday), setLog("Squat", 3, tuesday), setLog("Squat", 3, wednesday))
        val gap = buildWeeklyWorkoutGap(sessionsPlanned = 3, setsThisWeek = logs, zone = zone)
        assertEquals(3, gap.target)
        assertEquals(3, gap.actual)
        assertEquals(0, gap.gap)
    }

    @Test
    fun `an empty week reduces to PROVEN, per combinedTier's own doc comment on an empty receiver`() {
        val gap = buildWeeklyWorkoutGap(sessionsPlanned = 3, setsThisWeek = emptyList(), zone = zone)
        assertEquals(0, gap.actual)
        assertEquals(3, gap.gap)
        assertEquals(TrustTier.PROVEN, gap.tier)
    }

    @Test
    fun `any logged session makes the gap REPORTED - D6, one reported actual taints the whole gap`() {
        val gap = buildWeeklyWorkoutGap(sessionsPlanned = 3, setsThisWeek = listOf(setLog("Squat", 3, monday)), zone = zone)
        assertEquals(TrustTier.REPORTED, gap.tier)
    }

    @Test
    fun `weekStartEpoch snaps to the same Monday for every day inside that week`() {
        val mondayAgain = weekStartEpoch(tuesday, zone)
        assertEquals(monday, mondayAgain)
        assertEquals(monday, weekStartEpoch(wednesday, zone))
    }

    /**
     * The regression: a set logged in the evening west of UTC is already the NEXT day in UTC, so
     * the old UTC-boundary session count either credited it to the wrong day or, on a Sunday
     * evening, pushed it out of the week entirely - while `util.shortDate` rendered the row's own
     * date correctly in local time. Row right, aggregate wrong.
     */
    @Test
    fun `two evening sessions west of UTC count as two days, not one`() {
        val fri = ZonedDateTime.of(2026, 8, 7, 21, 0, 0, 0, zone).toInstant().toEpochMilli()
        val sat = ZonedDateTime.of(2026, 8, 8, 21, 0, 0, 0, zone).toInstant().toEpochMilli()
        val gap = buildWeeklyWorkoutGap(
            sessionsPlanned = 3,
            setsThisWeek = listOf(setLog("Squat", 3, fri), setLog("Bench Press", 3, sat)),
            zone = zone,
        )
        assertEquals(2, gap.actual)
    }

    /** A Sunday-evening set belongs to the week that is ending, not the one that starts in UTC. */
    @Test
    fun `a Sunday evening set west of UTC still falls inside that week's window`() {
        val sundayEvening = ZonedDateTime.of(2026, 8, 9, 21, 0, 0, 0, zone).toInstant().toEpochMilli()
        val start = weekStartEpoch(sundayEvening, zone)
        val end = weekEndEpoch(sundayEvening, zone)
        assertTrue(sundayEvening >= start && sundayEvening < end)
        assertEquals(DayOfWeek.MONDAY, Instant.ofEpochMilli(start).atZone(zone).dayOfWeek)
    }
}
