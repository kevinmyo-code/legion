package com.kevin.legion.meals

import android.content.Context
import com.kevin.legion.backend.BodyWriteThrough
import com.kevin.legion.backend.WriteThroughOutcome
import com.kevin.legion.backend.queuedSentence
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
     * Outcome of a voice/tool/dialog write, same shape and same reason as
     * [com.kevin.legion.workouts.WorkoutController.WriteOutcome], which is the original: [success]
     * is derived from what actually landed, never asserted by the caller. Added 2026-09-07 because
     * [logMeal] and [setTarget] returned a bare `String` and `LiveToolbox`'s `log_meal`/
     * `set_meal_target` dispatch hardcoded `success = true` above them - so a server REFUSAL came
     * back to the model as `{"success": true, "message": "That meal didn't go through: ..."}` and
     * CLAUDE.md section 7's outcome-verb rule, which is conditioned on the tool RESULT, had a lying
     * flag to stand on.
     */
    data class WriteOutcome(val success: Boolean, val message: String)

    /**
     * D25: hands [spokenDescription] to [MealAgent], then writes a [MealLog] row from whatever
     * it returns. Unlike [com.kevin.legion.pantry.PantryController.importReceipt] there is no
     * gate to fail here (see [MealAgent]'s doc comment) - a meal log is written even if some
     * macro fields come back null, because the description itself is the thing being recorded,
     * not a number that must reconcile against anything. D37: [TrustTier.REPORTED] stamped here
     * unconditionally, same as [com.kevin.legion.workouts.WorkoutController.logSet].
     */
    suspend fun logMeal(context: Context, spokenDescription: String): WriteOutcome {
        val estimate = MealAgent.estimateFromDescription(spokenDescription)
        val now = System.currentTimeMillis()
        val description = estimate?.description ?: spokenDescription
        // BodyWriteThrough.addMealLog, not a direct DAO insert (body-supabase ticket) - writes
        // locally first, unconditionally, then pushes to Supabase if configured, enqueueing on
        // failure. guid/updatedAtMs are minted here, at the one place a MealLog is ever created.
        val outcome = BodyWriteThrough.addMealLog(
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
        //
        // A REFUSED write reached no table at all on the server-first path, so nothing here may
        // say "logged" - the server's own sentence goes back instead
        // (`.scratch/django-engine/issues/15-*`, CLAUDE.md §7's outcome-verb rule). A blank
        // description is the live case: `meal_logs.description` is `length(trim(...)) > 0` on both
        // servers, and neither this function nor `log_meal`'s dispatch has ever checked it.
        if (outcome is WriteThroughOutcome.Refused) {
            return WriteOutcome(false, "That meal didn't go through: ${outcome.message}")
        }
        val queuedNote = (outcome as? WriteThroughOutcome.Queued<*>)?.let { " " + queuedSentence(it.reason) } ?: ""
        val spoken = if (estimate?.caloriesKcal != null) {
            "$description logged - roughly ${estimate.caloriesKcal} kcal (estimate).$queuedNote"
        } else {
            "$description logged - couldn't put a calorie estimate on it, but it's recorded.$queuedNote"
        }
        return WriteOutcome(true, spoken)
    }

    /** D26: sets the driver's daily calorie/macro target, effective from today (D2's "copy forward"). */
    suspend fun setTarget(
        context: Context,
        caloriesKcal: Int,
        proteinG: Double,
        carbsG: Double,
        fatG: Double,
    ): WriteOutcome {
        val now = System.currentTimeMillis()
        val dayStart = dayStartEpoch(now)
        val db = CarDatabase.getDatabase(context)
        // Reuse the existing row's guid on a same-day re-set - see MealTargetDao.getByEffectiveDate's
        // own doc comment for why a fresh guid here would orphan a server row.
        val guid = db.mealTargetDao().getByEffectiveDate(dayStart)?.guid ?: UUID.randomUUID().toString()
        // BodyWriteThrough.setMealTarget, not a direct DAO upsert - see logMeal's own comment.
        val outcome = BodyWriteThrough.setMealTarget(
            context,
            MealTarget(
                caloriesKcal = caloriesKcal, proteinG = proteinG, carbsG = carbsG, fatG = fatG,
                effectiveFromDateEpoch = dayStart, updatedAt = now,
                guid = guid,
            ),
        )
        if (outcome is WriteThroughOutcome.Refused) {
            return WriteOutcome(false, "That target didn't go through: ${outcome.message}")
        }
        val queuedNote = (outcome as? WriteThroughOutcome.Queued<*>)?.let { " " + queuedSentence(it.reason) } ?: ""
        return WriteOutcome(
            true,
            "Daily target set: $caloriesKcal kcal, ${proteinG}g protein, ${carbsG}g carbs, ${fatG}g fat.$queuedNote",
        )
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
