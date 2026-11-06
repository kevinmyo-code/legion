package com.kevin.legion.bodycoach

import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.finance.noteOrNull
import com.kevin.legion.finance.valueOrNull
import java.time.LocalDate
import java.time.temporal.ChronoUnit
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
    fun todayResult(read: FinanceRead<WorkoutTodayWire>, today: LocalDate = LocalDate.now()): JSONObject {
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
        // Workout reads (2026-11-06): "and the one after?" is answerable from this read too.
        w.upcoming.firstOrNull { !it.isToday }?.let { out.put("next_session_after_today", session(it, today)) }
        return out
    }

    /**
     * `get_workout_programme`'s result (workout reads, 2026-11-06): every day of the programme, the
     * training days, today's status, the sessions ahead with their dates, and the week. Kevin's
     * first voice session asked "what's the next day", "what are the three sessions" and "Day B",
     * and a today-only read made Alfred say there was no Day B. Every number and date is the
     * engine's; [today] only turns a date into "today" or "tomorrow".
     *
     * **Not sent is not absent.** An engine older than this read sends no `days`; the result then
     * fails in words that say the programme is unknown here, never that a day does not exist.
     */
    fun programmeResult(read: FinanceRead<WorkoutTodayWire>, today: LocalDate = LocalDate.now()): JSONObject {
        val w = read.valueOrNull
            ?: return JSONObject()
                .put("success", false)
                .put("message", (read.noteOrNull ?: "The workout programme could not be read.") + " Nothing is known about the programme.")
        if (w.days.isEmpty() || w.upcoming.isEmpty()) {
            return JSONObject()
                .put("success", false)
                .put(
                    "message",
                    "The engine did not send the whole programme (it may need an update), so the other " +
                        "days and the sessions ahead are unknown here, not missing. Never say a day does " +
                        "not exist. Today's read: ${w.words}",
                )
        }
        val out = JSONObject()
            .put("success", true)
            .put("words", w.programmeWords)
            // Body coach 2 (Kevin, 2026-11-06): days are flexible, a sessions-a-week target with no
            // fixed weekdays; an older engine still sends weekdays, said as its usual days.
            .put("programme", w.programme?.summary?.ifBlank { null } ?: "Full body, Day A and Day B alternating")
            .put("sessions_per_week", w.week.planned)
            .put("sessions_done_this_week", w.week.done)
            .put("sessions_planned_this_week", w.week.planned)
            .put(
                "today",
                JSONObject()
                    .put("date", w.today)
                    .put("weekday", w.todayWeekday)
                    .put("training_day", w.trainingDay)
                    .put("status", TODAY_STATUS[w.todayStatus] ?: w.todayStatus),
            )
            .put("next_session", session(w.upcoming.first(), today))
            .put("rule", "Every target and date comes from the programme's rules. Say them as given; never invent or change one. 'Your pick' is a calibration: a weight they could lift for 2 more reps than the top of the range.")
        w.upcoming.firstOrNull { !it.isToday }?.let { out.put("next_session_after_today", session(it, today)) }
        out.put("upcoming", JSONArray(w.upcoming.map { session(it, today) }))
        val days = JSONArray()
        for (d in w.days) {
            days.put(
                JSONObject()
                    .put("day", d.dayLabel.ifBlank { "Day ${d.dayKey}" })
                    .put("exercises", JSONArray(d.exercises.map { target(it) }))
                    .put("next_on", d.nextOn?.let { "${d.nextWeekday.orEmpty()} $it".trim() } ?: JSONObject.NULL)
                    .put("calibrating", d.calibrating),
            )
        }
        out.put("days", days)
        read.noteOrNull?.let { out.put("saved_copy", it) }
        return out
    }

    /** "Day B, Monday 2026-11-09", "Day A, today, Friday 2026-11-06", "Day B, tomorrow, ...". */
    fun session(u: UpcomingWire, today: LocalDate): String {
        val on = runCatching { LocalDate.parse(u.date) }.getOrNull()
        val gap = on?.let { ChronoUnit.DAYS.between(today, it) }
        val whenSaid = when {
            gap == null -> ""
            gap == 0L -> "today, "
            gap == 1L -> "tomorrow, "
            gap < 0L -> "a date already past (this copy is out of date), "
            else -> ""
        }
        val deload = if (u.deload) " (deload: lighter, one set fewer)" else ""
        val label = u.dayLabel.ifBlank { "Day ${u.dayKey}" }
        // No date from an engine with flexible days: the session's place in the rotation is all.
        if (on == null) return label + (if (u.isToday) ", the next one, due now" else "") + deload
        return "$label, $whenSaid${u.weekday} ${u.date}".replace("  ", " ").trim() + deload
    }

    // Body coach 2: days are flexible, so "rest" means this week's sessions are done.
    private val TODAY_STATUS = mapOf(
        "rest" to "this week's sessions are done",
        "not_started" to "a session is due",
        "in_progress" to "workout in progress",
        "done" to "today's workout is done",
    )

    /** One programme day on the card's "See all workouts": its label and each exercise's target. */
    data class DayView(val label: String, val calibrating: Boolean, val rows: List<Pair<String, String>>)

    /** Every day of the programme and the sessions ahead, for the card's expander. */
    data class AllWorkoutsView(val days: List<DayView>, val next: List<String>)

    /**
     * What the Body card's "See all workouts" shows (Kevin, 2026-11-06: "i cant see all the
     * workouts on the screen, i only see day A"): every day of the locked programme, each exercise
     * as sets x reps @ weight or "your pick" (calibration), then the next sessions. Null when the
     * engine sent no days (an older engine), so the card shows no expander rather than one day.
     * The same engine read `get_workout_programme` says by voice; every number is the rules'.
     */
    fun allWorkouts(w: WorkoutTodayWire?, today: LocalDate, sessionsShown: Int = 4): AllWorkoutsView? {
        if (w == null || w.days.isEmpty()) return null
        val days = w.days.map { d ->
            DayView(
                label = d.dayLabel.ifBlank { "Day ${d.dayKey}" },
                calibrating = d.calibrating || d.exercises.any { it.calibrating },
                rows = d.exercises.map { t -> t.name to target(t).removePrefix(t.name).trim() },
            )
        }
        return AllWorkoutsView(days, w.upcoming.take(sessionsShown).map { session(it, today) })
    }

    /** "This week: 2 of 3 sessions." */
    fun weekLine(week: WeekWire): String = "${week.done} of ${week.planned} sessions"
}
