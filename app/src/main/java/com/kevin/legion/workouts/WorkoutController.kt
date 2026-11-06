package com.kevin.legion.workouts

import android.content.Context
import com.kevin.legion.backend.BodyWriteThrough
import com.kevin.legion.backend.WriteThroughOutcome
import com.kevin.legion.backend.queuedSentence
import com.kevin.legion.data.local.BodyweightLog
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.WorkoutSetLog
import com.kevin.legion.data.local.WorkoutSetLogDao
import com.kevin.legion.plan.PlanGap
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.util.shortDate
import java.util.UUID

/**
 * Orchestrates the workouts aspect - mirrors [com.kevin.legion.pantry.PantryController]'s shape
 * (a thin Context/Room-reading wrapper around pure logic), which
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
     *
     * 2026-09-07: [generatePlan] and [logBodyweight] return it too, for the same defect found
     * again after the server-first change - `create_workout_plan`/`log_bodyweight` hardcoded
     * `success = true` over messages that had already learnt to say "didn't go through".
     * [com.kevin.legion.meals.MealController.WriteOutcome] and
     * [com.kevin.legion.sleep.SleepController.WriteOutcome] are the same shape for the same reason.
     */
    data class WriteOutcome(val success: Boolean, val message: String)

    /**
     * **No model writes a workout any more (workouts, 2026-11-06).** This used to hand [goal] to a
     * Gemini sub-agent that invented exercises and set counts. Training is now the engine's
     * deterministic programme (`server/body_coach/programme.py`): 3 full-body days, A and B,
     * targets from double progression. So this sets that programme up on the engine and says so;
     * [goal] is not used to change a number, ever. Kept under its old name because the goal plan
     * ([com.kevin.legion.advisor.GoalPlanAgent.accept]) still calls it for a plan's workout piece.
     *
     * [WriteOutcome.success] is the engine's own 2xx; with no engine it says nothing was set up.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun generatePlan(context: Context, goal: String): WriteOutcome {
        val out = com.kevin.legion.bodycoach.WorkoutSessionController.forApp(context).setUpProgramme()
        return WriteOutcome(out.ok, if (out.ok) out.sentence else "I couldn't set up the workout programme. ${out.sentence}")
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

        val outcome = BodyWriteThrough.addWorkoutSetLog(
            context,
            WorkoutSetLog(
                exercise = exercise,
                sets = sets,
                reps = reps,
                weightValue = weightValue,
                weightUnit = weightUnit,
                loggedAt = loggedAt,
                trustTier = TrustTier.REPORTED,
                sourceListItemId = sourceListItemId,
                guid = UUID.randomUUID().toString(),
                updatedAtMs = loggedAt,
            ),
        )
        // D34: the tool response states what was written, no separate confirm turn.
        //
        // **This comment used to end "a push failure is queued, never lost, and never reported as
        // a failure to the driver (CLAUDE.md's outcome-verb rule cuts the other way here: LOCAL
        // success is what 'logged' asserts, matching every other body write)."** That reading held
        // while the local write was unconditional. It no longer is on the Django transport
        // (`.scratch/django-engine/issues/15-*`): a REFUSED write never reaches Room at all, so
        // there is no local success left to assert and success is derived from the outcome branch.
        // A QUEUED write is still a real local write and still reads as logged - with the queue
        // said in words after it, which is ADR 0044 rule 4 rather than a hedge.
        val row = outcome.row ?: return WriteOutcome(
            false,
            "That set didn't go through: ${(outcome as WriteThroughOutcome.Refused).message}",
        )
        val weightPhrase = if (weightValue != null) " at $weightValue${weightUnit ?: ""}" else ""
        val repsPhrase = if (reps != null) " of $reps" else ""
        val queuedNote = (outcome as? WriteThroughOutcome.Queued<*>)?.let { " " + queuedSentence(it.reason) } ?: ""
        return WriteOutcome(true, "${row.sets} sets$repsPhrase of ${row.exercise}$weightPhrase, logged.$queuedNote")
    }

    /**
     * D23: bodyweight is its own reported measurement, not a field on [WorkoutSetLog].
     *
     * **Nothing in Kotlin has ever checked [weightValue] or [weightUnit] here**, and that is not an
     * oversight this ticket introduced - `log_bodyweight`'s `args.optDouble("weight")` happily
     * yields 0.0 for a missing or unparseable argument. `weight_value > 0` and
     * `weight_unit in ('lbs','kg')` are the server's rules (`server/api/body.py`,
     * `supabase/.../aspect_body.sql`), and on the server-first path they are now enforced BEFORE
     * anything reaches Room instead of after.
     */
    suspend fun logBodyweight(context: Context, weightValue: Double, weightUnit: String): WriteOutcome {
        val now = System.currentTimeMillis()
        val outcome = BodyWriteThrough.addBodyweightLog(
            context,
            BodyweightLog(
                weightValue = weightValue, weightUnit = weightUnit, loggedAt = now, trustTier = TrustTier.REPORTED,
                guid = UUID.randomUUID().toString(), updatedAtMs = now,
            ),
        )
        if (outcome is WriteThroughOutcome.Refused) {
            return WriteOutcome(false, "That bodyweight didn't go through: ${outcome.message}")
        }
        val queuedNote = (outcome as? WriteThroughOutcome.Queued<*>)?.let { " " + queuedSentence(it.reason) } ?: ""
        return WriteOutcome(true, "Bodyweight logged: $weightValue $weightUnit.$queuedNote")
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
        BodyWriteThrough.deleteWorkoutSetLog(context, log)
        return "Undone: ${log.sets} sets of ${log.exercise} logged ${shortDate(log.loggedAt)}."
    }

    suspend fun deleteBodyweightLog(context: Context, log: BodyweightLog): String {
        BodyWriteThrough.deleteBodyweightLog(context, log)
        return "Undone: bodyweight ${log.weightValue} ${log.weightUnit} logged ${shortDate(log.loggedAt)}."
    }
}
