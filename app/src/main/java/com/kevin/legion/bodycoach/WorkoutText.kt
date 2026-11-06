package com.kevin.legion.bodycoach

import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.finance.noteOrNull
import com.kevin.legion.finance.valueOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * The workout in words, for the voice tools and the Body screen (workouts, 2026-11-06). The numbers
 * are the engine's rules' (`server/body_coach/programme.py`); this file only says them. "Your pick"
 * is a calibration target, never a number the phone made up.
 */
object WorkoutText {

    /** "Bench press 3 x 6-8 @ 125 lb", "Plank 2 x 30-45 s", "Back squat 3 x 6-8 @ your pick". */
    fun target(name: String, sets: Int, low: Int, high: Int, unit: String, kind: String, weightLbs: Double?): String {
        val reps = if (low == high) "$low" else "$low-$high"
        val seconds = if (unit == "seconds") " s" else ""
        val at = when {
            kind != "weighted" -> ""
            weightLbs == null -> " @ your pick"
            else -> " @ ${lbs(weightLbs)} lb"
        }
        return "$name $sets x $reps$seconds$at"
    }

    fun target(t: TargetWire): String = target(t.name, t.sets, t.repsLow, t.repsHigh, t.unit, t.kind, t.weightLbs)

    fun lbs(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else "%.1f".format(value)

    /** One exercise's sets in a session, grouped: (name, sets in order). */
    fun exercises(session: SessionWire): List<Pair<String, List<SetWire>>> =
        session.sets.groupBy { it.exerciseKey }.values.map { it.first().name to it.sortedBy { s -> s.setNumber } }

    /** What one set says: "8 reps @ 125 lb", "40 s", "@ your pick". */
    fun setValue(s: SetWire): String {
        val reps = s.reps ?: s.repsHigh
        val unit = if (s.unit == "seconds") "s" else "reps"
        val weight = s.weightLbs ?: s.targetWeightLbs
        val at = when {
            !s.weighted -> ""
            weight == null -> " @ your pick"
            else -> " @ ${lbs(weight)} lb"
        }
        return "$reps $unit$at"
    }

    /** `get_todays_workout`'s result: the engine's words, a saved copy's age, and the session. */
    fun todayResult(read: FinanceRead<WorkoutTodayWire>): JSONObject {
        val w = read.valueOrNull
            ?: return JSONObject()
                .put("success", false)
                .put("message", (read.noteOrNull ?: "The workout could not be read.") + " Nothing is known about today's workout.")
        val out = JSONObject()
            .put("success", true)
            .put("words", w.words)
            .put("training_day", w.trainingDay)
            .put("sessions_done_this_week", w.week.done)
            .put("sessions_planned_this_week", w.week.planned)
            .put("rule", "Every target comes from the programme's rules. Explain one with its words; never invent or change a number.")
        read.noteOrNull?.let { out.put("saved_copy", it) }
        w.session?.let { s ->
            out.put("session_status", s.status).put("sets_done", s.setsDone).put("set_count", s.setCount)
            val arr = JSONArray()
            for ((name, sets) in exercises(s)) {
                arr.put(
                    JSONObject()
                        .put("exercise", name)
                        .put("target", sets.first().let { target(name, sets.count { !it.extra }, it.repsLow, it.repsHigh, it.unit, it.kind, it.targetWeightLbs) })
                        .put("ticked", sets.filter { it.done }.joinToString(", ") { setValue(it) }),
                )
            }
            out.put("exercises", arr)
        }
        if (w.session == null) {
            w.next?.let { p ->
                out.put("next_day", p.dayKey).put("calibrating", p.calibrating).put("deload", p.deload)
                val arr = JSONArray()
                for (t in p.exercises) arr.put(JSONObject().put("target", target(t)).put("why", t.words))
                out.put("exercises", arr)
            }
        }
        return out
    }

    /** "This week: 2 of 3 sessions." */
    fun weekLine(week: WeekWire): String = "${week.done} of ${week.planned} sessions"
}
