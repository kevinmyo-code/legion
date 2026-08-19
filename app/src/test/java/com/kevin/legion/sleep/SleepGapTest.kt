package com.kevin.legion.sleep

import com.kevin.legion.data.local.SleepLog
import com.kevin.legion.plan.TrustTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [buildSleepGap] and [parseSleepDurationMinutes] - plain JUnit, pure by construction.
 * Mirrors [com.kevin.legion.meals.MealGapTest]'s shape. All fixtures invented.
 */
class SleepGapTest {

    private var nextId = 1L

    private fun sleep(minutes: Int, quality: Int? = null) = SleepLog(
        id = nextId++, sleepDate = 1_733_356_800_000L, durationMinutes = minutes, quality = quality,
        loggedAt = 1_733_356_800_000L, trustTier = TrustTier.REPORTED,
    )

    // ------------------------------------------------------------- duration parsing

    @Test
    fun `a decimal hours figure rounds to whole minutes`() {
        assertEquals(450, parseSleepDurationMinutes(7.5))
        assertEquals(480, parseSleepDurationMinutes(8.0))
        // 7.483... hours -> 449.0 minutes rounds to 449
        assertEquals(449, parseSleepDurationMinutes(7.4833333))
    }

    @Test
    fun `zero, negative, NaN, and infinite durations are all rejected`() {
        assertNull(parseSleepDurationMinutes(0.0))
        assertNull(parseSleepDurationMinutes(-3.0))
        assertNull(parseSleepDurationMinutes(Double.NaN))
        assertNull(parseSleepDurationMinutes(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `a duration past 24 hours is rejected as a transcription guard`() {
        assertNull(parseSleepDurationMinutes(24.1))
        assertEquals(1440, parseSleepDurationMinutes(24.0)) // exactly 24h is still valid
    }

    // --------------------------------------------------------------------- gap

    @Test
    fun `zero nights logged is NotLogged, never a zero-actual gap`() {
        val result = buildSleepGap(480, emptyList())
        assertTrue(result is SleepGap.NotLogged)
    }

    @Test
    fun `no target set is also NotLogged - nothing to compute a gap against`() {
        val result = buildSleepGap(null, listOf(sleep(420)))
        assertTrue(result is SleepGap.NotLogged)
    }

    @Test
    fun `sleeping exactly the target reads a zero gap`() {
        val result = buildSleepGap(480, listOf(sleep(480))) as SleepGap.Logged
        assertEquals(0, result.gap.gap)
    }

    @Test
    fun `sleeping under target reads a positive gap - short of target`() {
        val result = buildSleepGap(480, listOf(sleep(390))) as SleepGap.Logged
        assertEquals(90, result.gap.gap)
    }

    @Test
    fun `sleeping over target reads a negative gap - over target`() {
        val result = buildSleepGap(420, listOf(sleep(500))) as SleepGap.Logged
        assertEquals(-80, result.gap.gap)
    }

    @Test
    fun `multiple rows for the same night sum together`() {
        val result = buildSleepGap(480, listOf(sleep(300), sleep(60))) as SleepGap.Logged
        assertEquals(360, result.gap.actual)
    }

    @Test
    fun `every logged night is REPORTED tier - nothing external ever verifies sleep`() {
        val result = buildSleepGap(480, listOf(sleep(420))) as SleepGap.Logged
        assertEquals(TrustTier.REPORTED, result.gap.tier)
    }
}
