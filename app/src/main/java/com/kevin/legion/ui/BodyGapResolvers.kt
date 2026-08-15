package com.kevin.legion.ui

import com.kevin.legion.data.local.BodyweightLog
import com.kevin.legion.data.local.MealLog
import com.kevin.legion.data.local.SleepLog
import com.kevin.legion.data.local.WorkoutSetLog
import com.kevin.legion.meals.dayStartEpoch
import com.kevin.legion.sleep.formatMinutesAsHours
import com.kevin.legion.ui.common.DeckPoint
import com.kevin.legion.ui.common.bucketDailyAverage
import com.kevin.legion.ui.common.dailyBuckets
import com.kevin.legion.util.shortDate
import java.time.ZoneId

/**
 * Pure formatters for [BodyScreen]'s recent-activity lists - the same "Compose-free mapper, thin
 * row wrapper" split [TodayGapResolvers.kt] uses for [com.kevin.legion.ui.common.GapRow]. These
 * feed [com.kevin.legion.ui.common.ReadingRow] instead, since a recent log entry is a plain
 * reading (what happened), not a target-versus-actual gap.
 */

/** D22: "three sets of squats at 225" - [WorkoutSetLog.reps]/[WorkoutSetLog.weightValue] are both nullable (partial voice input), so both phrases are optional and never fabricated when absent. */
fun workoutSetValueText(log: WorkoutSetLog): String {
    val repsPhrase = log.reps?.let { " x $it" } ?: ""
    val weightPhrase = log.weightValue?.let { " @ $it${log.weightUnit ?: ""}" } ?: ""
    return "${log.sets} sets$repsPhrase$weightPhrase"
}

/** CLAUDE.md §4 rule 5: a meal's calorie figure is always an LLM estimate, never printed by anything - the word "estimate" travels with the number rather than being implied by context. [MealLog.caloriesKcal] null means the extraction produced no usable figure (see that entity's doc comment), not that the meal had zero calories. */
fun mealValueText(log: MealLog): String =
    log.caloriesKcal?.let { "$it kcal (estimate)" } ?: "no calorie estimate"

/** "7h 30m, quality 4/5" - [SleepLog.quality] is optional (the driver may not say one), so the quality phrase only appears when it exists. */
fun sleepValueText(log: SleepLog): String {
    val qualityPhrase = log.quality?.let { ", quality $it/5" } ?: ""
    return "${formatMinutesAsHours(log.durationMinutes)}$qualityPhrase"
}

/** "185 lbs" / "84.5 kg" - trims a whole-number reading to no decimal rather than always forcing one, since a driver who says "185" said a whole number and "185.0" reads as a precision the reading never had. */
fun formatWeight(value: Double, unit: String): String {
    val whole = value == Math.floor(value)
    val number = if (whole) value.toLong().toString() else "%.1f".format(value)
    return "$number $unit"
}

/**
 * D23: bodyweight is a plain reported measurement with "its own target later" - there is no gap to
 * compute yet, only a trend against the PREVIOUS reading, and only when it exists. Returns null
 * when there is no previous reading to compare against (a single logged weight has no trend), or
 * when the two readings are in different units - never converts lbs to kg or vice versa, matching
 * [com.kevin.legion.ui.ledger.BalancesSection]'s "never invent an exchange rate" posture applied to
 * a unit conversion instead of a currency one.
 */
fun bodyweightTrendText(latest: BodyweightLog, previous: BodyweightLog?): String? {
    if (previous == null || previous.weightUnit != latest.weightUnit) return null
    val delta = latest.weightValue - previous.weightValue
    if (delta == 0.0) return "no change since ${shortDate(previous.loggedAt)}"
    val sign = if (delta > 0) "+" else ""
    return "$sign${"%.1f".format(delta)} ${latest.weightUnit} since ${shortDate(previous.loggedAt)}"
}

// ---------------------------------------------------------- ticket 16: chart bucketing

/**
 * Ticket 16's chart-facing bucketing layer for [BodyScreen]'s four panels + drilldowns. Pure -
 * no Room, no Compose - same "pure builder, thin Composable wrapper" split
 * [com.kevin.legion.ui.common.DeckChartData.kt] itself is built on; unit-tested in
 * `BodyGapResolversTest` with no Robolectric. Every function here honours that file's one
 * invariant too: a day nothing was logged for is a GAP (`null`), never a zero - see each
 * function's own doc for how that reads for its particular metric.
 */

/**
 * MASS panel's 30d sparkline / drilldown line series: one [DeckPoint] per local day, averaged
 * when more than one reading landed the same day, a GAP for a day nothing was logged
 * ([bucketDailyAverage] does the grouping, reused rather than reimplemented). [unit] NEVER
 * converts across units - the same "never invent an exchange rate" posture [bodyweightTrendText]
 * documents for the trend line - so a sample logged in a different unit than [unit] is silently
 * dropped from the chart rather than plotted on the wrong scale. Callers pass whichever unit the
 * LATEST reading is in, so a driver who has only ever logged in one unit sees every point; a
 * driver who switched units mid-history sees only the run in their current one.
 */
fun bucketBodyweightDaily(
    samples: List<BodyweightLog>,
    unit: String,
    startMs: Long,
    endMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<DeckPoint?> {
    val pairs = samples.filter { it.weightUnit == unit }.map { it.loggedAt to it.weightValue.toFloat() }
    return bucketDailyAverage(pairs, startMs, endMs, zone)
}

/**
 * INTAKE panel's 7d bars / drilldown series: one total-kcal `Int` per local day (keyed by
 * [MealLog.loggedAt]), `null` (gap) for a day with NO meal rows logged at all. A day that HAS a
 * meal logged but no usable calorie estimate for any of it (see [MealLog.caloriesKcal]'s doc
 * comment) sums to `0` by the exact same convention [com.kevin.legion.meals.buildDailyMealGap]
 * already uses for the gap row: "a null macro field contributes 0 to that axis's sum... the meal
 * itself WAS logged." That `0` can therefore only ever appear on a day with at least one real
 * meal row, so it reads as "logged, no usable estimate" rather than "logged and ate nothing" -
 * the CLAUDE.md §4 rule 6 distinction this whole file exists to keep straight, extended from a
 * single day's gap row onto a multi-day chart.
 */
fun bucketMealKcalDaily(meals: List<MealLog>, startMs: Long, endMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<Int?> {
    val days = dailyBuckets(startMs, endMs, zone)
    if (days.isEmpty()) return emptyList()
    val grouped = meals.groupBy { dayStartEpoch(it.loggedAt, zone) }
    return days.map { dayStart ->
        val bucket = grouped[dayStart]
        if (bucket.isNullOrEmpty()) null else bucket.sumOf { it.caloriesKcal ?: 0 }
    }
}

/**
 * SLEEP panel's 7d bars / drilldown series: one night's duration in minutes per local day-bucket,
 * keyed by [SleepLog.sleepDate] (the wake-date convention - see that field's doc comment, and
 * [com.kevin.legion.sleep.SleepController.gapFor]'s matching read), `null` when no night was
 * logged for that date. Sums rather than picks a single row when more than one [SleepLog] shares
 * a [SleepLog.sleepDate] (an edge case normal use never produces, but the gap-computation this
 * mirrors, [SleepGap][com.kevin.legion.sleep.SleepGap], already sums its window the same way, so
 * this stays consistent with it rather than silently picking one and disagreeing).
 */
fun bucketSleepMinutesDaily(nights: List<SleepLog>, startMs: Long, endMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<Int?> {
    val days = dailyBuckets(startMs, endMs, zone)
    if (days.isEmpty()) return emptyList()
    val grouped = nights.groupBy { dayStartEpoch(it.sleepDate, zone) }
    return days.map { dayStart ->
        val bucket = grouped[dayStart]
        if (bucket.isNullOrEmpty()) null else bucket.sumOf { it.durationMinutes }
    }
}

/**
 * TRAINING's second drilldown level - the "biohacker's payoff chart" ticket 07 names: one
 * [DeckPoint] per session-day, `y` = the MAX [WorkoutSetLog.weightValue] logged for [sets]'
 * shared exercise that day. A driver who does "3x5 at 225" then, later the same day, "AMRAP at
 * 135" has one session; the chart plots the day's heaviest work, matching how a lifter actually
 * reads a progression chart (the top set, not an average blended with a warmup ramp).
 *
 * Sets with a `null` [WorkoutSetLog.weightValue] (a bodyweight exercise, or partial voice input
 * that never named a number - see that field's doc comment) contribute NOTHING to this chart -
 * they still belong in the drilldown's plain history list (ticket 16: "sets without weight
 * excluded from the chart but listed"), just never averaged or zero-filled into a numeric axis.
 * A day with sets logged but EVERY one missing a weight is therefore a chart GAP, not a zero,
 * exactly like an unlogged day - the one metric in this file that can legitimately have nothing
 * numeric to plot even on a day something real happened, so the null-contributes-nothing shape
 * matters here more than anywhere else in the kit.
 */
fun buildExerciseProgression(sets: List<WorkoutSetLog>, startMs: Long, endMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<DeckPoint?> {
    val days = dailyBuckets(startMs, endMs, zone)
    if (days.isEmpty()) return emptyList()
    val weighted = sets.filter { it.weightValue != null }
    val grouped = weighted.groupBy { dayStartEpoch(it.loggedAt, zone) }
    return days.map { dayStart ->
        val bucket = grouped[dayStart]
        if (bucket.isNullOrEmpty()) null else DeckPoint(xMs = dayStart, y = bucket.maxOf { it.weightValue!!.toFloat() })
    }
}
