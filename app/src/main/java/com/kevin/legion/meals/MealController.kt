package com.kevin.legion.meals

import android.content.Context
import com.kevin.legion.backend.BodyWriteThrough
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MealLog
import com.kevin.legion.data.local.MealTarget
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.util.shortDate
import java.util.UUID

/**
 * Orchestrates the meals aspect - mirrors [com.kevin.legion.pantry.PantryController]'s and
 * [com.kevin.legion.workouts.WorkoutController]'s shape.
 */
object MealController {
    /**
     * D25: hands [spokenDescription] to [MealAgent], then writes a [MealLog] row from whatever
     * it returns. Unlike [com.kevin.legion.pantry.PantryController.importReceipt] there is no
     * gate to fail here (see [MealAgent]'s doc comment) - a meal log is written even if some
     * macro fields come back null, because the description itself is the thing being recorded,
     * not a number that must reconcile against anything. D37: [TrustTier.REPORTED] stamped here
     * unconditionally, same as [com.kevin.legion.workouts.WorkoutController.logSet].
     */
    suspend fun logMeal(context: Context, spokenDescription: String): String {
        val estimate = MealAgent.estimateFromDescription(spokenDescription)
        val now = System.currentTimeMillis()
        val description = estimate?.description ?: spokenDescription
        // BodyWriteThrough.addMealLog, not a direct DAO insert (body-supabase ticket) - writes
        // locally first, unconditionally, then pushes to Supabase if configured, enqueueing on
        // failure. guid/updatedAtMs are minted here, at the one place a MealLog is ever created.
        BodyWriteThrough.addMealLog(
            context,
            MealLog(
                description = description,
                caloriesKcal = estimate?.caloriesKcal,
                proteinG = estimate?.proteinG,
                carbsG = estimate?.carbsG,
                fatG = estimate?.fatG,
                loggedAt = now,
                sourceImagePath = null,
                trustTier = TrustTier.REPORTED,
                guid = UUID.randomUUID().toString(),
                updatedAtMs = now,
            ),
        )
        // D34: no separate confirm turn - state what was written, in words the driver can catch
        // a mishearing from. Estimate fields are always spoken as estimates (CLAUDE.md §4 rule 5).
        return if (estimate?.caloriesKcal != null) {
            "$description logged - roughly ${estimate.caloriesKcal} kcal (estimate)."
        } else {
            "$description logged - couldn't put a calorie estimate on it, but it's recorded."
        }
    }

    /** D26: sets the driver's daily calorie/macro target, effective from today (D2's "copy forward"). */
    suspend fun setTarget(context: Context, caloriesKcal: Int, proteinG: Double, carbsG: Double, fatG: Double): String {
        val now = System.currentTimeMillis()
        val dayStart = dayStartEpoch(now)
        val db = CarDatabase.getDatabase(context)
        // Reuse the existing row's guid on a same-day re-set - see MealTargetDao.getByEffectiveDate's
        // own doc comment for why a fresh guid here would orphan a server row.
        val guid = db.mealTargetDao().getByEffectiveDate(dayStart)?.guid ?: UUID.randomUUID().toString()
        // BodyWriteThrough.setMealTarget, not a direct DAO upsert - see logMeal's own comment.
        BodyWriteThrough.setMealTarget(
            context,
            MealTarget(
                caloriesKcal = caloriesKcal, proteinG = proteinG, carbsG = carbsG, fatG = fatG,
                effectiveFromDateEpoch = dayStart, updatedAt = now,
                guid = guid,
            ),
        )
        return "Daily target set: $caloriesKcal kcal, ${proteinG}g protein, ${carbsG}g carbs, ${fatG}g fat."
    }

    /** D27's gap, computed impossible-to-misread by construction - see [DailyMealGap]'s doc comment. */
    suspend fun dayGap(context: Context, now: Long = System.currentTimeMillis()): DailyMealGap {
        val dayStart = dayStartEpoch(now)
        val dayEnd = dayEndEpoch(now)
        val db = CarDatabase.getDatabase(context)
        val target = db.mealTargetDao().currentTarget(dayStart)?.let {
            MacroTotals(it.caloriesKcal, it.proteinG, it.carbsG, it.fatG)
        }
        val mealsToday = db.mealLogDao().forWindow(dayStart, dayEnd)
        return buildDailyMealGap(target, mealsToday)
    }

    suspend fun recentMeals(context: Context, limit: Int = 20): List<MealLog> =
        CarDatabase.getDatabase(context).mealLogDao().getRecent(limit)

    /**
     * INTAKE panel + drilldown (ticket 16): every meal within [fromMs, nowMs) - the daily-kcal
     * bucketing source ([com.kevin.legion.ui.bucketMealKcalDaily]) and the drilldown's history list
     * both read from this one call.
     */
    suspend fun mealsInWindow(context: Context, fromMs: Long, nowMs: Long = System.currentTimeMillis()): List<MealLog> =
        CarDatabase.getDatabase(context).mealLogDao().forWindow(fromMs, nowMs)

    /** See [com.kevin.legion.workouts.WorkoutController]'s matching doc comment - the pick across domains happens in LiveToolbox. */
    suspend fun mostRecentMealLog(context: Context): MealLog? =
        CarDatabase.getDatabase(context).mealLogDao().mostRecent()

    suspend fun deleteMealLog(context: Context, log: MealLog): String {
        // BodyWriteThrough.deleteMealLog - soft-deletes and pushes the tombstone on a configured
        // install, hard-deletes on an unconfigured one. See that function's own doc comment.
        BodyWriteThrough.deleteMealLog(context, log)
        return "Undone: ${log.description} logged ${shortDate(log.loggedAt)}."
    }
}
