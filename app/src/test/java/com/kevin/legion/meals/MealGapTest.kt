package com.kevin.legion.meals

import com.kevin.legion.data.local.MealLog
import com.kevin.legion.plan.TrustTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Exercises [buildDailyMealGap] (D27) - plain JUnit, pure by construction. All fixtures invented.
 */
class MealGapTest {

    private var nextId = 1L

    private fun meal(
        calories: Int? = 500,
        protein: Double? = 30.0,
        carbs: Double? = 40.0,
        fat: Double? = 15.0,
    ) = MealLog(
        id = nextId++, description = "Meal", caloriesKcal = calories, proteinG = protein,
        carbsG = carbs, fatG = fat, loggedAt = 1_733_356_800_000L, trustTier = TrustTier.REPORTED,
    )

    private val target = MacroTotals(caloriesKcal = 2200, proteinG = 150.0, carbsG = 200.0, fatG = 70.0)

    @Test
    fun `zero meals logged today is NotLogged, never a zero-actual gap - D27`() {
        val result = buildDailyMealGap(target, emptyList())
        assertTrue(result is DailyMealGap.NotLogged)
    }

    @Test
    fun `no target set is also NotLogged - nothing to compute a gap against`() {
        val result = buildDailyMealGap(null, listOf(meal()))
        assertTrue(result is DailyMealGap.NotLogged)
    }

    @Test
    fun `logged meals sum into the actual, and the gap is target minus actual`() {
        val result = buildDailyMealGap(target, listOf(meal(calories = 500), meal(calories = 700))) as DailyMealGap.Logged
        assertEquals(1200, result.gap.actual.caloriesKcal)
        assertEquals(1000, result.gap.gap.caloriesKcal) // 2200 - 1200
    }

    @Test
    fun `a null macro field on one logged meal contributes zero to that axis, the meal still counts`() {
        val result = buildDailyMealGap(target, listOf(meal(calories = null, protein = 20.0))) as DailyMealGap.Logged
        assertEquals(0, result.gap.actual.caloriesKcal)
        assertEquals(20.0, result.gap.actual.proteinG, 0.001)
    }

    @Test
    fun `every meal today reported makes the whole gap REPORTED - D6 via combinedTier`() {
        val result = buildDailyMealGap(target, listOf(meal())) as DailyMealGap.Logged
        assertEquals(TrustTier.REPORTED, result.gap.tier)
    }

    @Test
    fun `dayStartEpoch snaps any time in a day to that day's midnight in the given zone`() {
        val chicago = ZoneId.of("America/Chicago")
        val noon = 1_733_400_000_000L
        val midnight = dayStartEpoch(noon, chicago)
        assertEquals(midnight, dayStartEpoch(midnight + 23L * 60 * 60 * 1000, chicago))
    }

    /**
     * The regression this file existed without: an evening meal west of UTC. Every fixture here
     * used to be built in UTC, so a UTC-versus-device-zone boundary mismatch was invisible to the
     * suite by construction - the same blind spot
     * [com.kevin.legion.notes.Recurrence.DEFAULT_ZONE] documents for the calendar domain.
     */
    @Test
    fun `an evening meal west of UTC falls inside its OWN local day, not the next one`() {
        val chicago = ZoneId.of("America/Chicago")
        // 21:00 on Aug 7 in Chicago is already Aug 8 in UTC - the exact instant the old
        // UTC-boundary window dropped out of "today".
        val evening = ZonedDateTime.of(2026, 8, 7, 21, 0, 0, 0, chicago).toInstant().toEpochMilli()
        val dayStart = dayStartEpoch(evening, chicago)
        val dayEnd = dayEndEpoch(evening, chicago)

        assertTrue(evening >= dayStart && evening < dayEnd)
        assertEquals(
            LocalDate.of(2026, 8, 7),
            Instant.ofEpochMilli(dayStart).atZone(chicago).toLocalDate(),
        )
    }

    /** A local day is 23 hours on a spring-forward date, which a fixed `+ 24h` end would overrun. */
    @Test
    fun `dayEndEpoch follows the calendar across a DST shift, not a fixed 24 hours`() {
        val chicago = ZoneId.of("America/Chicago")
        val springForward = ZonedDateTime.of(2026, 3, 8, 12, 0, 0, 0, chicago).toInstant().toEpochMilli()
        val span = dayEndEpoch(springForward, chicago) - dayStartEpoch(springForward, chicago)
        assertEquals(23L * 60 * 60 * 1000, span)
    }
}
