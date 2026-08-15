package com.kevin.legion.workouts

import android.content.Context
import com.kevin.legion.data.local.BodyweightLog
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.WorkoutPlan
import com.kevin.legion.data.local.WorkoutPlanItem
import com.kevin.legion.data.local.WorkoutSetLog
import com.kevin.legion.data.local.WorkoutSetLogDao
import com.kevin.legion.plan.PlanGap
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.util.shortDate

/**
 * Orchestrates the workouts aspect - mirrors [com.kevin.legion.pantry.PantryController]'s shape
 * (a thin Context/Room-reading wrapper around pure logic + a [WorkoutPlanAgent] call), which
 * itself mirrors [com.kevin.legion.ledger.LedgerController].
 */
object WorkoutController {
    /**
     * D21: hands the driver's stated [goal] to [WorkoutPlanAgent], then stores the result as the
     * new current [WorkoutPlan] + [WorkoutPlanItem] rows, effective from THIS week (D2's "copy
     * forward" - nothing before this week's boundary is touched or deleted). Returns a spoken
     * summary on success, or a failure message on any step going wrong - never a half-written
     * plan (if the agent call fails or returns nothing usable, nothing is written).
     */
    suspend fun generatePlan(context: Context, goal: String): String {
        val draft = WorkoutPlanAgent.write(goal)
            ?: return "I couldn't put a plan together just now - try again in a sec."

        val now = System.currentTimeMillis()
        val weekStart = weekStartEpoch(now)
        val db = CarDatabase.getDatabase(context)
        db.workoutPlanDao().upsert(
            WorkoutPlan(sessionsPerWeek = draft.sessionsPerWeek, effectiveFromWeekEpoch = weekStart, updatedAt = now)
        )
        db.workoutPlanItemDao().upsertAll(
            draft.exercises.map { (exercise, targetSets) ->
                WorkoutPlanItem(
                    exercise = exercise,
                    targetSetsPerWeek = targetSets,
                    effectiveFromWeekEpoch = weekStart,
                    updatedAt = now,
                )
            }
        )
        val exerciseList = draft.exercises.entries.joinToString(", ") { "${it.key} (${it.value} sets/week)" }
        return "Plan set: ${draft.sessionsPerWeek} sessions a week - $exerciseList."
    }

    /**
     * D22: writes one set-group log entry. [exercise]/[sets] are the "important missing piece" the
     * `log_workout_set` tool declares `required` (ticket 11 D35) - everything else here is
     * optional detail that may simply not have been said. D37: [TrustTier.REPORTED] is stamped
     * here, at the write site, unconditionally - there is no branch that could forget it.
     */
    suspend fun logSet(
        context: Context,
        exercise: String,
        sets: Int,
        reps: Int?,
        weightValue: Double?,
        weightUnit: String?,
    ): String {
        val now = System.currentTimeMillis()
        CarDatabase.getDatabase(context).workoutSetLogDao().insert(
            WorkoutSetLog(
                exercise = exercise,
                sets = sets,
                reps = reps,
                weightValue = weightValue,
                weightUnit = weightUnit,
                loggedAt = now,
                trustTier = TrustTier.REPORTED,
            )
        )
        // D34: the tool response states what was written, no separate confirm turn.
        val weightPhrase = if (weightValue != null) " at $weightValue${weightUnit ?: ""}" else ""
        val repsPhrase = if (reps != null) " of $reps" else ""
        return "$sets sets$repsPhrase of $exercise$weightPhrase, logged."
    }

    /** D23: bodyweight is its own reported measurement, not a field on [WorkoutSetLog]. */
    suspend fun logBodyweight(context: Context, weightValue: Double, weightUnit: String): String {
        val now = System.currentTimeMillis()
        CarDatabase.getDatabase(context).bodyweightLogDao().insert(
            BodyweightLog(weightValue = weightValue, weightUnit = weightUnit, loggedAt = now, trustTier = TrustTier.REPORTED)
        )
        return "Bodyweight logged: $weightValue $weightUnit."
    }

    /**
     * D24's one required gap - sessions done versus sessions planned, this week - as of [now].
     * Returns null when no plan has been set yet (nothing to compute a gap against, same "no
     * anchor, no claim" posture [com.kevin.legion.meals.buildDailyMealGap] uses for a missing
     * [com.kevin.legion.data.local.MealTarget]).
     */
    suspend fun weekGap(context: Context, now: Long = System.currentTimeMillis()): PlanGap<Int>? {
        val weekStart = weekStartEpoch(now)
        val weekEnd = weekEndEpoch(now)
        val db = CarDatabase.getDatabase(context)
        val plan = db.workoutPlanDao().currentPlan(weekStart) ?: return null
        val setsThisWeek = db.workoutSetLogDao().forWindow(weekStart, weekEnd)
        return buildWeeklyWorkoutGap(plan.sessionsPerWeek, setsThisWeek)
    }

    suspend fun recentSets(context: Context, limit: Int = 20): List<WorkoutSetLog> =
        CarDatabase.getDatabase(context).workoutSetLogDao().getRecent(limit)

    suspend fun recentBodyweights(context: Context, limit: Int = 10): List<BodyweightLog> =
        CarDatabase.getDatabase(context).bodyweightLogDao().getRecent(limit)

    /**
     * MASS panel + drilldown (ticket 16): every bodyweight reading within [fromMs, nowMs),
     * ascending - both the sparkline/line-chart source ([com.kevin.legion.ui.bucketBodyweightDaily])
     * and, reversed, the drilldown's history list read from this one call.
     */
    suspend fun bodyweightHistory(context: Context, fromMs: Long, nowMs: Long = System.currentTimeMillis()): List<BodyweightLog> =
        CarDatabase.getDatabase(context).bodyweightLogDao().forWindow(fromMs, nowMs)

    /** TRAINING drilldown's exercise-list level (ticket 16): distinct exercises, most recent first. */
    suspend fun exercisesByRecency(context: Context): List<WorkoutSetLogDao.ExerciseRecency> =
        CarDatabase.getDatabase(context).workoutSetLogDao().distinctExercisesByRecency()

    /** TRAINING drilldown's per-exercise progression level (ticket 16): every set ever logged under [exercise], oldest first. */
    suspend fun setsForExercise(context: Context, exercise: String): List<WorkoutSetLog> =
        CarDatabase.getDatabase(context).workoutSetLogDao().forExercise(exercise)

    /**
     * Ticket 11 D36's "undo the last thing" tool needs to compare candidates across every
     * newly voice-writable table (workout sets, bodyweight, AND meals -
     * [com.kevin.legion.meals.MealController]) to find the single most recent one, so the actual
     * pick-and-delete decision lives in [com.kevin.legion.service.LiveToolbox]'s `undo_last_log`
     * dispatch, not here. These two "peek" reads plus [deleteMostRecentSetLog]/
     * [deleteMostRecentBodyweightLog] are what that dispatch calls.
     */
    suspend fun mostRecentSetLog(context: Context): WorkoutSetLog? =
        CarDatabase.getDatabase(context).workoutSetLogDao().mostRecent()

    suspend fun mostRecentBodyweightLog(context: Context): BodyweightLog? =
        CarDatabase.getDatabase(context).bodyweightLogDao().mostRecent()

    suspend fun deleteSetLog(context: Context, log: WorkoutSetLog): String {
        CarDatabase.getDatabase(context).workoutSetLogDao().deleteById(log.id)
        return "Undone: ${log.sets} sets of ${log.exercise} logged ${shortDate(log.loggedAt)}."
    }

    suspend fun deleteBodyweightLog(context: Context, log: BodyweightLog): String {
        CarDatabase.getDatabase(context).bodyweightLogDao().deleteById(log.id)
        return "Undone: bodyweight ${log.weightValue} ${log.weightUnit} logged ${shortDate(log.loggedAt)}."
    }
}
