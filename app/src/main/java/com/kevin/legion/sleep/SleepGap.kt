package com.kevin.legion.sleep

import com.kevin.legion.data.local.SleepLog
import com.kevin.legion.plan.PlanGap
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.plan.combinedTier

/**
 * Sleep is a new body domain (Kevin, 2026-08-07: "i want to be able to log sleep too"), modelled
 * directly on [com.kevin.legion.meals.buildDailyMealGap]/[com.kevin.legion.workouts.buildWeeklyWorkoutGap]'s
 * shape. Pure - no Room, no Android - so this is a plain JUnit test, matching every other
 * plan-versus-actual pure function in the app.
 *
 * **Trust tier: REPORTED, always.** Nothing external verifies how long a person slept - there is
 * no reconciliation gate here at all (CLAUDE.md §4's gate only ever applies where a source
 * document states its own anchor to check extraction against), the same "never has an anchor, by
 * the nature of the thing being logged" posture [SleepLog]'s own doc comment states for
 * [MealLog][com.kevin.legion.data.local.MealLog]. A figure mixing sleep with any PROVEN data must
 * say so - `.scratch/legion-shape/issues/02-trust-tiers.md`.
 */

/**
 * The max a single night can validly be, as a transcription guard (a misheard "seven hours" as
 * "seventy hours" is a parsing failure, not a real sleep - same discipline as
 * [com.kevin.legion.ledger.PENDING_AMOUNT_MAX_CENTS]), not a real physiological limit.
 */
const val SLEEP_DURATION_MAX_MINUTES = 24 * 60

/**
 * Converts a spoken duration in hours (accepts a decimal, e.g. "7.5 hours") to whole minutes,
 * rounding with [Math.round] exactly once, at this write boundary - never re-derived downstream.
 * Returns null for anything unloggable: non-finite, non-positive, or past
 * [SLEEP_DURATION_MAX_MINUTES] once rounded - the same non-positive/overflow guard shape
 * [com.kevin.legion.ledger.pendingAmountCents] uses for a spoken dollar amount.
 */
fun parseSleepDurationMinutes(hours: Double): Int? {
    if (hours.isNaN() || hours.isInfinite() || hours <= 0.0) return null
    val minutes = Math.round(hours * 60.0)
    if (minutes <= 0L || minutes > SLEEP_DURATION_MAX_MINUTES) return null
    return minutes.toInt()
}

/**
 * The gap, computed impossible-to-misread by construction - see [com.kevin.legion.meals.DailyMealGap]'s
 * doc comment for why a sealed class (rather than a nullable/zero-coerced [PlanGap]) is what makes
 * "no data logged" structurally distinguishable from "logged zero".
 */
sealed class SleepGap {
    /**
     * Covers BOTH "no [com.kevin.legion.data.local.SleepTarget] has ever been set" and "a target
     * exists but nothing was logged for this night" - same fold [com.kevin.legion.meals.DailyMealGap.NotLogged]
     * uses, because both cases share the one true statement a caller can make: there is nothing to
     * compute a gap against yet. A caller distinguishing the two reasons for its own copy (see
     * [com.kevin.legion.ui.BodyContent]'s meal-gap empty state) does so with its own separate
     * "is a target set" read, exactly as the meals screen already does.
     */
    object NotLogged : SleepGap()
    data class Logged(val gap: PlanGap<Int>) : SleepGap()
}

/**
 * Builds one night's gap from already-fetched rows and target - pure, mirrors
 * [com.kevin.legion.meals.buildDailyMealGap] field for field. [sleepThatNight] EMPTY is
 * [SleepGap.NotLogged] unconditionally, never a coerced zero-actual [PlanGap] (the same D27 rule
 * meals already follows). Multiple rows for the same night (unusual, but not disallowed - a driver
 * correcting themselves, or logging a nap separately) sum by [SleepLog.durationMinutes], matching
 * how [com.kevin.legion.meals.buildDailyMealGap] sums multiple meals for the same day.
 */
fun buildSleepGap(targetMinutes: Int?, sleepThatNight: List<SleepLog>): SleepGap {
    if (targetMinutes == null || sleepThatNight.isEmpty()) return SleepGap.NotLogged
    val actualMinutes = sleepThatNight.sumOf { it.durationMinutes }
    return SleepGap.Logged(
        PlanGap(
            target = targetMinutes,
            actual = actualMinutes,
            gap = targetMinutes - actualMinutes,
            tier = sleepThatNight.map { it.trustTier }.combinedTier(),
        )
    )
}
