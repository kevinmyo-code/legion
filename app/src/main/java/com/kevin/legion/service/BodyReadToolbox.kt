package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.finance.noteOrNull
import com.kevin.legion.finance.valueOrNull
import com.kevin.legion.meals.MealController
import com.kevin.legion.service.LiveToolbox.result
import com.kevin.legion.util.documentDate
import com.kevin.legion.workouts.WorkoutController
import org.json.JSONArray
import org.json.JSONObject

/**
 * The body reads (`get_workout_gap`, `list_recent_workouts`, `get_meal_gap`, `list_recent_meals`,
 * `get_sleep_gap`, `list_recent_sleep`), split out of [LiveToolbox] (shrink pass, 2026-11-22) along
 * the [EngineToolbox.dispatch] pattern: [LiveToolbox.dispatch] runs its guards first, then asks this
 * object, which answers the names it owns and returns null for every other. The coach's own reads
 * are [BodyCoachToolbox]; macro figures here are estimates and say so.
 */
object BodyReadToolbox {

    /** Null when [name] is not one of these tools, so [LiveToolbox.dispatch] can fall through. */
    suspend fun dispatch(context: Context, name: String, args: JSONObject): JSONObject? = when (name) {
        "get_workout_gap" -> getWorkoutGap(context)
        "list_recent_workouts" -> listRecentWorkouts(context, args.optInt("count", 10))
        "get_meal_gap" -> getMealGap(context)
        "list_recent_meals" -> listRecentMeals(context, args.optInt("count", 10))
        "get_sleep_gap" -> getSleepGap(context)
        "list_recent_sleep" -> listRecentSleep(context, args.optInt("count", 10))
        else -> null
    }

    /** Sessions done of planned this week, from the engine's workout (workouts, 2026-11-06). */
    private suspend fun getWorkoutGap(context: Context): JSONObject {
        val read = com.kevin.legion.bodycoach.WorkoutSessionController.forApp(context).today()
        val week = read.valueOrNull?.week
            ?: return result(success = false, message = (read.noteOrNull ?: "The workout could not be read.") + " Nothing is known about this week.")
        val out = JSONObject()
            .put("success", true)
            .put("sessionsPlanned", week.planned)
            .put("sessionsDone", week.done)
            .put("sessionsRemaining", (week.planned - week.done).coerceAtLeast(0))
            .put("reported", true)
        read.noteOrNull?.let { out.put("savedCopy", it) }
        return out
    }

    /** Recent workout set logs. */
    private suspend fun listRecentWorkouts(context: Context, count: Int): JSONObject {
        val logs = WorkoutController.recentSets(context, count.coerceIn(1, 100))
        val arr = JSONArray()
        for (l in logs) {
            val o = JSONObject().put("exercise", l.exercise).put("sets", l.sets)
            l.reps?.let { o.put("reps", it) }
            l.weightValue?.let { o.put("weight", it) }
            l.weightUnit?.let { o.put("weightUnit", it) }
            arr.put(o)
        }
        return JSONObject().put("success", true).put("count", logs.size).put("sets", arr)
    }

    private suspend fun getMealGap(context: Context): JSONObject {
        return when (val gap = MealController.dayGap(context)) {
            is com.kevin.legion.meals.DailyMealGap.NotLogged ->
                JSONObject().put("success", true).put("logged", false)
                    .put("message", "Nothing logged today - not zero, just not logged yet.")
            is com.kevin.legion.meals.DailyMealGap.Logged -> JSONObject()
                .put("success", true)
                .put("logged", true)
                .put("caloriesTarget", gap.gap.target.caloriesKcal)
                .put("caloriesActual", gap.gap.actual.caloriesKcal)
                .put("caloriesRemaining", gap.gap.gap.caloriesKcal)
                .put("proteinTargetG", gap.gap.target.proteinG)
                .put("proteinActualG", gap.gap.actual.proteinG)
                .put("carbsTargetG", gap.gap.target.carbsG)
                .put("carbsActualG", gap.gap.actual.carbsG)
                .put("fatTargetG", gap.gap.target.fatG)
                .put("fatActualG", gap.gap.actual.fatG)
                .put("reported", gap.gap.tier == com.kevin.legion.plan.TrustTier.REPORTED)
        }
    }

    /** Recent meal logs - macro fields are estimates, see the tool's own description. */
    private suspend fun listRecentMeals(context: Context, count: Int): JSONObject {
        val meals = MealController.recentMeals(context, count.coerceIn(1, 100))
        val arr = JSONArray()
        for (m in meals) arr.put(mealRow(m))
        return JSONObject().put("success", true).put("count", meals.size).put("meals", arr)
    }

    /**
     * One `list_recent_meals` row. The date it was logged, as an ISO date and a weekday in the
     * phone's zone, the same local day [com.kevin.legion.meals.dayStartEpoch] buckets "today" by
     * (async-lookups ticket 07; ticket 04 run 3, F7: without it, "what did I eat yesterday" could
     * only be answered as "the records don't specify dates").
     */
    internal fun mealRow(
        m: com.kevin.legion.data.local.MealLog,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): JSONObject {
        val day = java.time.Instant.ofEpochMilli(m.loggedAt).atZone(zone).toLocalDate()
        val o = JSONObject()
            .put("description", m.description)
            .put("date", day.toString())
            .put("weekday", day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US))
        m.caloriesKcal?.let { o.put("estimatedCaloriesKcal", it) }
        m.proteinG?.let { o.put("estimatedProteinG", it) }
        m.carbsG?.let { o.put("estimatedCarbsG", it) }
        m.fatG?.let { o.put("estimatedFatG", it) }
        return o
    }

    /** Tonight's (today's wake-date's) sleep gap, worded for the model to speak. */
    private suspend fun getSleepGap(context: Context): JSONObject {
        return when (val gap = com.kevin.legion.sleep.SleepController.gapFor(context)) {
            is com.kevin.legion.sleep.SleepGap.NotLogged ->
                JSONObject().put("success", true).put("logged", false)
                    .put("message", "Nothing logged for last night - not zero, just not logged yet (or no target set).")
            is com.kevin.legion.sleep.SleepGap.Logged -> JSONObject()
                .put("success", true)
                .put("logged", true)
                .put("targetMinutes", gap.gap.target)
                .put("actualMinutes", gap.gap.actual)
                .put("gapMinutes", gap.gap.gap)
                .put("reported", gap.gap.tier == com.kevin.legion.plan.TrustTier.REPORTED)
        }
    }

    /** Recent sleep logs. */
    private suspend fun listRecentSleep(context: Context, count: Int): JSONObject {
        val logs = com.kevin.legion.sleep.SleepController.recentSleep(context, count.coerceIn(1, 100))
        val arr = JSONArray()
        for (l in logs) {
            val o = JSONObject()
                .put("date", documentDate(l.sleepDate))
                .put("durationMinutes", l.durationMinutes)
            l.quality?.let { o.put("quality", it) }
            l.notes?.let { o.put("notes", it) }
            arr.put(o)
        }
        com.kevin.legion.assistant.cards.DomainToolCards.sleep(
            logs.map { it.sleepDate to it.durationMinutes },
            runCatching { com.kevin.legion.sleep.SleepController.currentTargetMinutes(context) }.getOrNull(),
        )
        return JSONObject().put("success", true).put("count", logs.size).put("nights", arr)
    }
}
