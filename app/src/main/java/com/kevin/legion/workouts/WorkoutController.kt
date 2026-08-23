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
     * Outcome of a voice/tool write, same shape and same reason as
     * [com.kevin.legion.vehicle.VehicleController.WriteOutcome]: [success] is derived from what
     * actually landed (a validated input plus a completed insert), never asserted by the caller.
     * Added because [logSet] used to return a bare `String` and `LiveToolbox`'s `log_workout_set`
     * dispatch hardcoded `success = true` above it regardless of what that string said - the exact
     * defect class ticket 05 closed for `set_odometer`/`log_service`, found again here 2026-08-17
     * (a driver's spoken sets during a 21:42-21:45 session never reached `workout_set_logs`, and
     * the app told him they had). [message] is what the caller speaks either way.
     */
    data class WriteOutcome(val success: Boolean, val message: String)

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
                    // Ticket 08: only the exercises the model actually gave a rep count for carry
                    // one - draft.repsPerSet has no entry at all for the rest, and this stays null
                    // rather than inventing one (see WorkoutPlanItem.repsPerSet's own doc).
                    repsPerSet = draft.repsPerSet[exercise],
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
     *
     * Validates BEFORE writing (2026-08-17 fix): a blank [exercise] or a non-positive [sets] used
     * to be inserted as-is, producing a garbage row the driver had no reason to expect (nothing
     * upstream stopped a misheard or empty argument from reaching this far). Refused in words, same
     * "no anchor, no claim" posture the rest of this codebase uses for a bad write - never a
     * silent no-op. [WriteOutcome.success] on the write path is derived from the DAO's own
     * `insert` returning a real row id (Room's autoincrement id is always > 0 for a landed row),
     * not asserted - this closes the exact gap `log_workout_set`'s dispatch used to hardcode.
     *
     * [loggedAt] defaults to "now" for every existing caller (voice, the dialog) - both report a
     * set as it happens. Ticket 08's end-of-day auto-log is the one caller that passes something
     * else: a TICKED past day's own local midnight, never the moment the sweep itself runs, so a
     * log written by tonight's app-open still reads as having happened on the day it was ticked
     * for, same "REPORTED, not observed live" trust tier either way (D37) - the ticket ruled this
     * is the same trust tier a spoken log gets, "he reported it either way".
     *
     * [sourceListItemId] (v33, ticket 09): null for every voice/dialog caller, exactly as before
     * this parameter existed - only [com.kevin.legion.advisor.GoalChecklistSync]'s sweep passes a
     * real id, naming the [com.kevin.legion.data.local.ListItem] whose tick produced this row. See
     * [com.kevin.legion.data.local.WorkoutSetLog.sourceListItemId]'s own doc for what reads it back.
     */
    suspend fun logSet(
        context: Context,
        exercise: String,
        sets: Int,
        reps: Int?,
        weightValue: Double?,
        weightUnit: String?,
        loggedAt: Long = System.currentTimeMillis(),
        sourceListItemId: Long? = null,
    ): WriteOutcome {
        if (exercise.isBlank())
            return WriteOutcome(false, "I didn't catch which exercise - say the name and I'll log it.")
        if (sets <= 0)
            return WriteOutcome(false, "That's not a set count I can log - how many sets?")

        val rowId = CarDatabase.getDatabase(context).workoutSetLogDao().insert(
            WorkoutSetLog(
                exercise = exercise,
                sets = sets,
                reps = reps,
                weightValue = weightValue,
                weightUnit = weightUnit,
                loggedAt = loggedAt,
                trustTier = TrustTier.REPORTED,
                sourceListItemId = sourceListItemId,
            )
        )
        // D34: the tool response states what was written, no separate confirm turn.
        val weightPhrase = if (weightValue != null) " at $weightValue${weightUnit ?: ""}" else ""
        val repsPhrase = if (reps != null) " of $reps" else ""
        return if (rowId > 0) {
            WriteOutcome(true, "$sets sets$repsPhrase of $exercise$weightPhrase, logged.")
        } else {
            WriteOutcome(false, "That didn't save - try logging it again.")
        }
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
