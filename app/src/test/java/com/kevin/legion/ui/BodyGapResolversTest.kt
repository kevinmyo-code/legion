package com.kevin.legion.ui

import com.kevin.legion.data.local.BodyweightLog
import com.kevin.legion.data.local.MealLog
import com.kevin.legion.data.local.SleepLog
import com.kevin.legion.data.local.WorkoutSetLog
import com.kevin.legion.plan.TrustTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Exercises the pure formatters in `BodyGapResolvers.kt` - plain JUnit, no Compose/Android
 * dependency. All fixtures are invented.
 */
class BodyGapResolversTest {

    // ------------------------------------------------------------- workout sets

    @Test
    fun `a full set with reps and weight formats all three parts`() {
        val log = WorkoutSetLog(id = 1, exercise = "Squat", sets = 3, reps = 5, weightValue = 225.0, weightUnit = "lbs", loggedAt = 0, trustTier = TrustTier.REPORTED)
        assertEquals("3 sets x 5 @ 225.0lbs", workoutSetValueText(log))
    }

    @Test
    fun `a bodyweight exercise with no weight omits the weight phrase entirely - never fabricates a number`() {
        val log = WorkoutSetLog(id = 1, exercise = "Pushups", sets = 3, reps = 15, weightValue = null, weightUnit = null, loggedAt = 0, trustTier = TrustTier.REPORTED)
        assertEquals("3 sets x 15", workoutSetValueText(log))
    }

    @Test
    fun `no reps and no weight is just the set count`() {
        val log = WorkoutSetLog(id = 1, exercise = "Sprints", sets = 5, reps = null, weightValue = null, weightUnit = null, loggedAt = 0, trustTier = TrustTier.REPORTED)
        assertEquals("5 sets", workoutSetValueText(log))
    }

    // ------------------------------------------------------------------- meals

    @Test
    fun `a meal with a calorie estimate says so explicitly - CLAUDE_md rule 5`() {
        val log = MealLog(id = 1, description = "Burrito", caloriesKcal = 720, proteinG = null, carbsG = null, fatG = null, loggedAt = 0, trustTier = TrustTier.REPORTED)
        assertEquals("720 kcal (estimate)", mealValueText(log))
    }

    @Test
    fun `a meal with no usable extraction reads as no estimate, never a fabricated zero`() {
        val log = MealLog(id = 1, description = "Protein shake", caloriesKcal = null, proteinG = null, carbsG = null, fatG = null, loggedAt = 0, trustTier = TrustTier.REPORTED)
        assertEquals("no calorie estimate", mealValueText(log))
    }

    // --------------------------------------------------------------- bodyweight

    @Test
    fun `a whole-number weight reading has no decimal`() {
        assertEquals("185 lbs", formatWeight(185.0, "lbs"))
    }

    @Test
    fun `a fractional weight reading keeps one decimal`() {
        assertEquals("183.5 lbs", formatWeight(183.5, "lbs"))
    }

    @Test
    fun `a single logged weight has no trend to report`() {
        val latest = BodyweightLog(id = 1, weightValue = 185.0, weightUnit = "lbs", loggedAt = 0, trustTier = TrustTier.REPORTED)
        assertNull(bodyweightTrendText(latest, previous = null))
    }

    @Test
    fun `a lighter reading than before reads as a negative trend, no unit conversion invented across mismatched units`() {
        val latest = BodyweightLog(id = 2, weightValue = 183.5, weightUnit = "lbs", loggedAt = 1_000, trustTier = TrustTier.REPORTED)
        val previous = BodyweightLog(id = 1, weightValue = 185.0, weightUnit = "lbs", loggedAt = 0, trustTier = TrustTier.REPORTED)
        assertEquals(true, bodyweightTrendText(latest, previous)!!.startsWith("-1.5 lbs"))
    }

    @Test
    fun `mismatched units never get converted or compared`() {
        val latest = BodyweightLog(id = 2, weightValue = 83.5, weightUnit = "kg", loggedAt = 1_000, trustTier = TrustTier.REPORTED)
        val previous = BodyweightLog(id = 1, weightValue = 185.0, weightUnit = "lbs", loggedAt = 0, trustTier = TrustTier.REPORTED)
        assertNull(bodyweightTrendText(latest, previous))
    }

    // ------------------------------------------------------------------- sleep

    @Test
    fun `a sleep log with a quality rating includes it`() {
        val log = SleepLog(id = 1, sleepDate = 0, durationMinutes = 450, quality = 4, loggedAt = 0, trustTier = TrustTier.REPORTED)
        assertEquals("7h 30m, quality 4/5", sleepValueText(log))
    }

    @Test
    fun `a sleep log with no quality omits the phrase entirely - never fabricates a rating`() {
        val log = SleepLog(id = 1, sleepDate = 0, durationMinutes = 480, quality = null, loggedAt = 0, trustTier = TrustTier.REPORTED)
        assertEquals("8h", sleepValueText(log))
    }

    // ------------------------------------------------------ ticket 16: chart bucketing

    @Test
    fun `bodyweight buckets by day and averages same-day readings, gap for a day nothing logged`() {
        val zone = ZoneId.of("UTC")
        val day0 = ZonedDateTime.of(2026, 8, 1, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val day0Evening = ZonedDateTime.of(2026, 8, 1, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        val day2 = ZonedDateTime.of(2026, 8, 3, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val samples = listOf(
            BodyweightLog(id = 1, weightValue = 184.0, weightUnit = "lbs", loggedAt = day0, trustTier = TrustTier.REPORTED),
            BodyweightLog(id = 2, weightValue = 186.0, weightUnit = "lbs", loggedAt = day0Evening, trustTier = TrustTier.REPORTED),
            BodyweightLog(id = 3, weightValue = 183.0, weightUnit = "lbs", loggedAt = day2, trustTier = TrustTier.REPORTED),
        )
        val series = bucketBodyweightDaily(samples, unit = "lbs", startMs = day0, endMs = day2, zone = zone)
        assertEquals(3, series.size)
        assertEquals(185f, series[0]!!.y) // averaged, not summed
        assertNull(series[1]) // day1: nothing logged, a GAP not a zero
        assertEquals(183f, series[2]!!.y)
    }

    @Test
    fun `a bodyweight reading logged in a different unit than requested is silently dropped, never converted`() {
        val zone = ZoneId.of("UTC")
        val day0 = ZonedDateTime.of(2026, 8, 1, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val samples = listOf(BodyweightLog(id = 1, weightValue = 83.5, weightUnit = "kg", loggedAt = day0, trustTier = TrustTier.REPORTED))
        val series = bucketBodyweightDaily(samples, unit = "lbs", startMs = day0, endMs = day0, zone = zone)
        assertEquals(1, series.size)
        assertNull(series[0])
    }

    @Test
    fun `meal kcal buckets by day, summing every meal, gap for a day with no meal rows at all`() {
        val zone = ZoneId.of("UTC")
        val day0 = ZonedDateTime.of(2026, 8, 1, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val day0Dinner = ZonedDateTime.of(2026, 8, 1, 19, 0, 0, 0, zone).toInstant().toEpochMilli()
        val day2 = ZonedDateTime.of(2026, 8, 3, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val meals = listOf(
            MealLog(id = 1, description = "Oatmeal", caloriesKcal = 400, loggedAt = day0, trustTier = TrustTier.REPORTED),
            MealLog(id = 2, description = "Steak", caloriesKcal = 800, loggedAt = day0Dinner, trustTier = TrustTier.REPORTED),
            MealLog(id = 3, description = "Salad", caloriesKcal = 350, loggedAt = day2, trustTier = TrustTier.REPORTED),
        )
        val bars = bucketMealKcalDaily(meals, startMs = day0, endMs = day2, zone = zone)
        assertEquals(3, bars.size)
        assertEquals(1200, bars[0]) // summed, not averaged
        assertNull(bars[1]) // no meal rows that day - a gap
        assertEquals(350, bars[2])
    }

    @Test
    fun `a meal logged with no usable calorie estimate sums to zero on a day that DID have a meal, never confused with an unlogged day`() {
        val zone = ZoneId.of("UTC")
        val day0 = ZonedDateTime.of(2026, 8, 1, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val meals = listOf(MealLog(id = 1, description = "Protein shake", caloriesKcal = null, loggedAt = day0, trustTier = TrustTier.REPORTED))
        val bars = bucketMealKcalDaily(meals, startMs = day0, endMs = day0, zone = zone)
        assertEquals(1, bars.size)
        assertEquals(0, bars[0]) // 0, but distinguishable from a gap because the row exists
    }

    @Test
    fun `sleep minutes bucket by the wake-date, gap for a night nothing was logged`() {
        val zone = ZoneId.of("UTC")
        val day0 = ZonedDateTime.of(2026, 8, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val day2 = ZonedDateTime.of(2026, 8, 3, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val nights = listOf(
            SleepLog(id = 1, sleepDate = day0, durationMinutes = 450, loggedAt = day0, trustTier = TrustTier.REPORTED),
            SleepLog(id = 2, sleepDate = day2, durationMinutes = 480, loggedAt = day2, trustTier = TrustTier.REPORTED),
        )
        val bars = bucketSleepMinutesDaily(nights, startMs = day0, endMs = day2, zone = zone)
        assertEquals(3, bars.size)
        assertEquals(450, bars[0])
        assertNull(bars[1])
        assertEquals(480, bars[2])
    }

    @Test
    fun `daily bucketing crosses a device-zone DST spring-forward boundary without losing or duplicating a day`() {
        // 2026-03-08 is when America/Chicago springs forward (a 23-hour local day) - the exact
        // case DeckChartData.kt's dailyBuckets/dayStartEpoch machinery documents walking by
        // LocalDate rather than a flat 24h-per-day millis stride to survive.
        val zone = ZoneId.of("America/Chicago")
        val beforeDst = ZonedDateTime.of(2026, 3, 7, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val onDstDay = ZonedDateTime.of(2026, 3, 8, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val afterDst = ZonedDateTime.of(2026, 3, 9, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val meals = listOf(
            MealLog(id = 1, description = "Before", caloriesKcal = 500, loggedAt = beforeDst, trustTier = TrustTier.REPORTED),
            MealLog(id = 2, description = "On the shift day", caloriesKcal = 600, loggedAt = onDstDay, trustTier = TrustTier.REPORTED),
            MealLog(id = 3, description = "After", caloriesKcal = 700, loggedAt = afterDst, trustTier = TrustTier.REPORTED),
        )
        val bars = bucketMealKcalDaily(meals, startMs = beforeDst, endMs = afterDst, zone = zone)
        // Exactly three day-buckets, one per calendar date, each with its own meal's total -
        // a millis-stride bug would either merge two of these into one bucket or split one
        // calendar day into two.
        assertEquals(3, bars.size)
        assertEquals(500, bars[0])
        assertEquals(600, bars[1])
        assertEquals(700, bars[2])
    }

    // ---------------------------------------------------- exercise progression extraction

    @Test
    fun `exercise progression plots the MAX weight per session-day, not an average across sets that day`() {
        val zone = ZoneId.of("UTC")
        val morning = ZonedDateTime.of(2026, 8, 1, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val evening = ZonedDateTime.of(2026, 8, 1, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        val sets = listOf(
            WorkoutSetLog(id = 1, exercise = "Squat", sets = 5, reps = 5, weightValue = 135.0, weightUnit = "lbs", loggedAt = morning, trustTier = TrustTier.REPORTED),
            WorkoutSetLog(id = 2, exercise = "Squat", sets = 3, reps = 5, weightValue = 225.0, weightUnit = "lbs", loggedAt = evening, trustTier = TrustTier.REPORTED),
        )
        val series = buildExerciseProgression(sets, startMs = morning, endMs = evening, zone = zone)
        assertEquals(1, series.size)
        assertEquals(225f, series[0]!!.y)
    }

    @Test
    fun `a session-day where every set is missing a weight is a chart gap, not a zero`() {
        val zone = ZoneId.of("UTC")
        val day0 = ZonedDateTime.of(2026, 8, 1, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val sets = listOf(
            WorkoutSetLog(id = 1, exercise = "Pushups", sets = 3, reps = 15, weightValue = null, weightUnit = null, loggedAt = day0, trustTier = TrustTier.REPORTED),
        )
        val series = buildExerciseProgression(sets, startMs = day0, endMs = day0, zone = zone)
        assertEquals(1, series.size)
        assertNull(series[0])
    }

    @Test
    fun `sets without a weight are excluded from the chart even on a day that also has a weighted set`() {
        val zone = ZoneId.of("UTC")
        val day0 = ZonedDateTime.of(2026, 8, 1, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val sets = listOf(
            WorkoutSetLog(id = 1, exercise = "Squat", sets = 3, reps = 5, weightValue = 225.0, weightUnit = "lbs", loggedAt = day0, trustTier = TrustTier.REPORTED),
            WorkoutSetLog(id = 2, exercise = "Squat", sets = 1, reps = null, weightValue = null, weightUnit = null, loggedAt = day0, trustTier = TrustTier.REPORTED),
        )
        val series = buildExerciseProgression(sets, startMs = day0, endMs = day0, zone = zone)
        assertEquals(1, series.size)
        assertEquals(225f, series[0]!!.y)
        assertTrue(sets.size == 2) // the un-weighted set is still in the caller's own list for the history row, just never reached the chart
    }
}
