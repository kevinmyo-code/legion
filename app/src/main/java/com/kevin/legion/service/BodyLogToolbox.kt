package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.ai.SubAgent
import com.kevin.legion.meals.MealController
import com.kevin.legion.service.LiveToolbox.PENDING_DATE_FORMAT
import com.kevin.legion.service.LiveToolbox.result
import com.kevin.legion.workouts.WorkoutController
import org.json.JSONObject

/**
 * The body writes (`log_workout_set`, `log_bodyweight`, `log_meal`, `set_meal_target`, `log_sleep`,
 * `set_sleep_target`, `undo_last_log`), split out of [LiveToolbox] (shrink pass, 2026-11-22) along
 * the [EngineToolbox.dispatch] pattern: [LiveToolbox.dispatch] runs its guards first, then asks this
 * object, which answers the names it owns and returns null for every other. Each write's success is
 * its controller's outcome. `set_meal_target` reads [userText], the words of the turn.
 */
object BodyLogToolbox {

    /** Null when [name] is not one of these tools, so [LiveToolbox.dispatch] can fall through. */
    suspend fun dispatch(
        context: Context,
        name: String,
        args: JSONObject,
        userText: String? = null,
    ): JSONObject? = when (name) {
        // success DERIVED from the controller's own WriteOutcome, never hardcoded - these five
        // dispatches (plus logSleep below) used to pass `success = true` over messages that
        // already said "didn't go through", so a refused write reached the model as
        // {"success": true, "message": "<a failure>"}. CANNOT_CLAUSE is conditioned on the tool
        // RESULT, so a lying flag defeats it outright. Fixed 2026-09-07, same shape
        // log_workout_set and remember already had.
        "log_workout_set" -> logWorkoutSet(context, args)
        "log_bodyweight" ->
            WorkoutController.logBodyweight(
                context, args.optDouble("weight"), args.optString("weight_unit", "lbs"),
            ).let { result(it.success, it.message) }
        "log_meal" ->
            MealController.logMeal(context, args.optString("description"))
                .let { result(it.success, it.message) }
        "set_meal_target" -> {
            // Coach voice fixes (2026-11-06): "lock it in" is the plan's lock, never this.
            val refusal = CoachRouting.setMealTargetRefusal(userText, args.optIntOrNull("calories"))
            if (refusal != null) {
                result(false, refusal)
            } else {
                // kcal and protein are the targets (Kevin's ruling): carbs and fat are not
                // taken from a voice call, and a missing protein comes from the rule.
                MealController.setTarget(
                    context,
                    caloriesKcal = args.optIntOrNull("calories"),
                    proteinG = args.optDoubleOrNull("protein_g"),
                    carbsG = null,
                    fatG = null,
                ).let { result(it.success, it.message) }
            }
        }
        "log_sleep" -> logSleep(context, args)
        "set_sleep_target" ->
            com.kevin.legion.sleep.SleepController.setTarget(context, args.optDouble("hours"))
                .let { result(it.success, it.message) }
        "undo_last_log" -> undoLastLog(context)
        else -> null
    }

    /**
     * Dispatches log_workout_set. `weight`/`weight_unit` travel together - a weight value with no
     * stated unit defaults to lbs rather than being dropped, since the driver clearly meant
     * something by naming a number.
     *
     * success is now DERIVED from [WorkoutController.logSet]'s [WorkoutController.WriteOutcome],
     * never hardcoded - same fix as set_odometer/log_service (comment at ~1708 above), applied
     * here 2026-08-17 after a measured on-device defect: a driver's spoken sets during a real
     * session never reached `workout_set_logs` (the write path never even ran that night - see
     * SubAgent's investigate-loop fix in the prior commit), yet the old `success = true` here would
     * have told him it was recorded even on a genuine write failure or a garbage argument.
     */
    /**
     * workouts, 2026-11-06: a spoken set ticks today's workout first. "Log bench 135 for 8" goes to
     * the engine's workout ([com.kevin.legion.bodycoach.WorkoutSessionController.logByName], the
     * same controller the Body screen's session view uses), once per set said, in pounds. Only when
     * the engine says the exercise is not in today's workout, or no reps were said, or the weight is
     * in kg, does it fall back to the standalone set log below - and the result says which happened.
     * An unreachable engine also falls back, so a set said at the gym is never lost.
     */
    private suspend fun logWorkoutSet(context: Context, args: JSONObject): JSONObject {
        val weight = if (args.has("weight")) args.optDouble("weight").takeIf { !it.isNaN() } else null
        val weightUnit = if (weight != null) args.optString("weight_unit", "lbs") else null
        val reps = if (args.has("reps")) args.optInt("reps") else null
        if (reps != null && weightUnit != "kg") {
            val sessions = com.kevin.legion.bodycoach.WorkoutSessionController.forApp(context)
            val count = args.optInt("sets", 1).coerceIn(1, 10)
            val said = mutableListOf<String>()
            for (i in 1..count) {
                val step = sessions.logByName(args.optString("exercise"), reps, weight)
                if (!step.ok) {
                    if (said.isNotEmpty()) return result(success = true, message = said.joinToString(" ") + " The rest did not go through: ${step.sentence}")
                    break
                }
                said += step.sentence
            }
            if (said.isNotEmpty()) return result(success = true, message = said.last())
        }
        val outcome = WorkoutController.logSet(
            context, args.optString("exercise"), args.optInt("sets"), reps, weight, weightUnit,
        )
        return result(success = outcome.success, message = outcome.message)
    }

    /**
     * Ticket 11 D36 (extended to sleep 2026-08-07): finds the single most recent row across the
     * four tables [log_workout_set], [log_bodyweight], [log_meal], and [log_sleep] can write,
     * deletes only that one, and says what it removed. This is the whole-domain "pick" step
     * [com.kevin.legion.workouts.WorkoutController]'s, [com.kevin.legion.meals.MealController]'s,
     * and [com.kevin.legion.sleep.SleepController]'s own doc comments defer to here, since no one
     * controller can see another's table. **Body logs only** - ledger's voice-logged pending
     * transactions stay exclusively on `clear_pending_transaction` and must NEVER join this list,
     * because undoing money is a different blast radius from undoing a meal (see this tool's own
     * declaration doc comment).
     */
    private suspend fun undoLastLog(context: Context): JSONObject {
        val lastSet = WorkoutController.mostRecentSetLog(context)
        val lastWeight = WorkoutController.mostRecentBodyweightLog(context)
        val lastMeal = MealController.mostRecentMealLog(context)
        val lastSleep = com.kevin.legion.sleep.SleepController.mostRecentSleepLog(context)

        val candidates = listOfNotNull(
            lastSet?.let { it.loggedAt to "set" },
            lastWeight?.let { it.loggedAt to "weight" },
            lastMeal?.let { it.loggedAt to "meal" },
            lastSleep?.let { it.loggedAt to "sleep" },
        )
        if (candidates.isEmpty()) {
            return result(success = false, message = "Nothing to undo - no workout, bodyweight, meal, or sleep logged yet.")
        }
        val mostRecentKind = candidates.maxByOrNull { it.first }!!.second
        val message = when (mostRecentKind) {
            "set" -> WorkoutController.deleteSetLog(context, lastSet!!)
            "weight" -> WorkoutController.deleteBodyweightLog(context, lastWeight!!)
            "sleep" -> com.kevin.legion.sleep.SleepController.deleteSleepLog(context, lastSleep!!)
            else -> MealController.deleteMealLog(context, lastMeal!!)
        }
        return result(success = true, message = message)
    }

    /**
     * Dispatches `log_sleep`. `date` reuses [PENDING_DATE_FORMAT] (MM/dd/yyyy) - the same spoken
     * date convention `log_pending_transaction` already uses, so the model doesn't have to learn a
     * second date shape for a second domain.
     */
    private suspend fun logSleep(context: Context, args: JSONObject): JSONObject {
        val dateRaw = args.optString("date").trim()
        val sleepDateOverride = if (dateRaw.isBlank()) null else try {
            // Device zone, because SleepLog.sleepDate is a DAY KEY cut by meals.dayStartEpoch and
            // the two must agree or a spoken override lands in the neighbouring day's window.
            java.time.LocalDate.parse(dateRaw, PENDING_DATE_FORMAT)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            return result(false, "I couldn't read that date - try month/day/year, like 08/07/2026.")
        }
        val quality = if (args.has("quality") && !args.isNull("quality")) args.optInt("quality") else null
        val notes = args.optString("notes").trim().takeIf { it.isNotBlank() }
        // success DERIVED, not hardcoded: SleepController.logSleep refuses an implausible duration
        // in words and a server-first REFUSED write reaches no table at all, and this dispatch used
        // to report both as success = true (see the dispatch block's own comment on
        // create_workout_plan).
        val outcome = com.kevin.legion.sleep.SleepController.logSleep(
            context = context,
            durationHours = args.optDouble("duration_hours"),
            quality = quality,
            notes = notes,
            sleepDateOverride = sleepDateOverride,
        )
        return result(success = outcome.success, message = outcome.message)
    }
}
