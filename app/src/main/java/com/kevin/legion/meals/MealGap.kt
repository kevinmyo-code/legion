package com.kevin.legion.meals

import com.kevin.legion.data.local.MealLog
import com.kevin.legion.plan.PlanGap
import com.kevin.legion.plan.combinedTier
import java.time.Instant
import java.time.ZoneId

/** [PlanGap]'s generic unit for a day's macro totals - target, actual, and gap are all this shape. */
data class MacroTotals(
    val caloriesKcal: Int,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
)

/**
 * D27: "An unlogged day reads 'not logged', never zero. Zero eaten is a lie, and a gap computed
 * against it would be confidently wrong - the exact failure class section 4 rule 6 covers." This
 * sealed class is what makes that failure IMPOSSIBLE BY CONSTRUCTION rather than merely avoided:
 * there is no [MacroTotals] value living inside [NotLogged], so a caller cannot accidentally
 * render "0 kcal" for a day with zero rows - the compiler forces a `when` to handle [NotLogged]
 * as its own branch before it can reach anywhere a number would be printed. This is the same
 * three-state discipline [com.kevin.legion.data.local.MaintenanceItem]'s
 * ANCHORED/UNKNOWN/NEVER-DONE split already uses in the fleet aspect, applied here to meals.
 */
sealed class DailyMealGap {
    object NotLogged : DailyMealGap()
    data class Logged(val gap: PlanGap<MacroTotals>) : DailyMealGap()
}

/**
 * Builds the day's gap from already-fetched rows and target. Pure - no Room, no Android - mirrors
 * [com.kevin.legion.ledger.buildBudgetVsActual]/[com.kevin.legion.workouts.buildWeeklyWorkoutGap]'s
 * shape.
 *
 * [mealsToday] EMPTY is [DailyMealGap.NotLogged] unconditionally (D27) - never coerced into a
 * zero-actual [PlanGap]. A row with a null macro field (an extraction that produced no usable
 * number for one axis, see [MealLog]'s doc comment) contributes 0 to that axis's sum rather than
 * being dropped entirely, since the meal itself WAS logged - only [mealsToday.isEmpty()] means
 * "not logged", not "one field came back null".
 *
 * [target] null (no [com.kevin.legion.data.local.MealTarget] set yet) also returns [DailyMealGap.NotLogged] -
 * there is nothing to compute a gap against, the same "no anchor, no claim" posture as an empty day.
 */
fun buildDailyMealGap(target: MacroTotals?, mealsToday: List<MealLog>): DailyMealGap {
    if (target == null || mealsToday.isEmpty()) return DailyMealGap.NotLogged
    val actual = MacroTotals(
        caloriesKcal = mealsToday.sumOf { it.caloriesKcal ?: 0 },
        proteinG = mealsToday.sumOf { it.proteinG ?: 0.0 },
        carbsG = mealsToday.sumOf { it.carbsG ?: 0.0 },
        fatG = mealsToday.sumOf { it.fatG ?: 0.0 },
    )
    val gapValue = MacroTotals(
        caloriesKcal = target.caloriesKcal - actual.caloriesKcal,
        proteinG = target.proteinG - actual.proteinG,
        carbsG = target.carbsG - actual.carbsG,
        fatG = target.fatG - actual.fatG,
    )
    return DailyMealGap.Logged(
        PlanGap(
            target = target,
            actual = actual,
            gap = gapValue,
            tier = mealsToday.map { it.trustTier }.combinedTier(),
        )
    )
}

/**
 * Day-start of the day containing [epochMs], **in the device's zone** - see
 * [com.kevin.legion.workouts.weekStartEpoch]'s doc comment for why this is device-zone and not
 * UTC, and for the bug that made it so.
 */
fun dayStartEpoch(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        .atStartOfDay(zone).toInstant().toEpochMilli()

/**
 * The EXCLUSIVE end of [dayStartEpoch]'s day - the next local midnight.
 *
 * Computed by advancing the calendar date rather than adding 24 hours, because a local day is 23
 * or 25 hours long on a DST-shift date and a fixed `+ 24h` would either overrun into the next day
 * or leave the last hour of this one outside the window. Now that these boundaries are device-zone
 * (see [com.kevin.legion.workouts.weekStartEpoch]) that case is reachable; it was not while they
 * were UTC.
 */
fun dayEndEpoch(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().plusDays(1)
        .atStartOfDay(zone).toInstant().toEpochMilli()
