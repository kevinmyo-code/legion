package com.kevin.legion.bodycoach

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The engine's workout bodies (`server/body_coach/workouts.py`, `GET /api/body/coach/workout/`),
 * field for field. **Every target here came from the engine's rules** (`programme.py`); the phone
 * shows them and sends back only what was done. Weights are pounds.
 */
@Serializable
data class WorkoutTodayWire(
    val programme: ProgrammeWire? = null,
    val today: String = "",
    @SerialName("training_day") val trainingDay: Boolean = false,
    val week: WeekWire = WeekWire(),
    /** Today's running workout, or today's finished one; null when neither. */
    val session: SessionWire? = null,
    /** The next workout's plan when none is running. */
    val next: PlanWire? = null,
    val words: String = "",
    // Workout reads (2026-11-06). Absent from an engine older than this; empty means NOT SENT,
    // never "there is no Day B" (WorkoutText.programmeResult says so in words).
    @SerialName("today_weekday") val todayWeekday: String = "",
    /** "rest", "not_started", "in_progress" or "done". */
    @SerialName("today_status") val todayStatus: String = "",
    /** Every day of the programme (A and B) with the rules' current targets. */
    val days: List<ProgrammeDayWire> = emptyList(),
    /** One week of coming sessions, counting today while today's is not done. */
    val upcoming: List<UpcomingWire> = emptyList(),
    @SerialName("programme_words") val programmeWords: String = "",
)

@Serializable
data class ProgrammeDayWire(
    @SerialName("day_key") val dayKey: String,
    @SerialName("next_on") val nextOn: String? = null,
    @SerialName("next_weekday") val nextWeekday: String? = null,
    val calibrating: Boolean = false,
    val exercises: List<TargetWire> = emptyList(),
)

@Serializable
data class UpcomingWire(
    @SerialName("day_key") val dayKey: String,
    val date: String,
    val weekday: String = "",
    @SerialName("session_number") val sessionNumber: Int = 0,
    val deload: Boolean = false,
    @SerialName("is_today") val isToday: Boolean = false,
)

@Serializable
data class ProgrammeWire(
    val id: String = "",
    val template: String = "",
    @SerialName("training_days") val trainingDays: List<String> = emptyList(),
    @SerialName("days_per_week") val daysPerWeek: Int = 3,
    val swaps: Map<String, String> = emptyMap(),
    @SerialName("started_on") val startedOn: String = "",
)

@Serializable
data class WeekWire(
    @SerialName("week_start") val weekStart: String = "",
    val done: Int = 0,
    val planned: Int = 3,
    val sessions: List<WeekSessionWire> = emptyList(),
    @SerialName("training_days") val trainingDays: List<String> = listOf("mon", "wed", "fri"),
)

@Serializable
data class WeekSessionWire(val date: String = "", @SerialName("day_key") val dayKey: String = "")

@Serializable
data class PlanWire(
    @SerialName("day_key") val dayKey: String = "A",
    @SerialName("session_number") val sessionNumber: Int = 1,
    val deload: Boolean = false,
    val calibrating: Boolean = false,
    @SerialName("set_count") val setCount: Int = 0,
    val exercises: List<TargetWire> = emptyList(),
)

@Serializable
data class TargetWire(
    @SerialName("exercise_key") val exerciseKey: String,
    val name: String,
    val kind: String = "weighted",
    @SerialName("slot_key") val slotKey: String = "",
    val sets: Int,
    @SerialName("reps_low") val repsLow: Int,
    @SerialName("reps_high") val repsHigh: Int,
    val unit: String = "reps",
    @SerialName("weight_lbs") val weightLbs: Double? = null,
    val calibrating: Boolean = false,
    val rule: String = "",
    val words: String = "",
    val alternatives: List<String> = emptyList(),
)

@Serializable
data class SessionWire(
    val id: String,
    @SerialName("day_key") val dayKey: String = "A",
    @SerialName("session_number") val sessionNumber: Int = 1,
    val deload: Boolean = false,
    val status: String = STATUS_IN_PROGRESS,
    @SerialName("session_date") val sessionDate: String = "",
    @SerialName("started_at") val startedAt: String = "",
    @SerialName("finished_at") val finishedAt: String? = null,
    @SerialName("sets_done") val setsDone: Int = 0,
    @SerialName("set_count") val setCount: Int = 0,
    val calibrating: Boolean = false,
    val sets: List<SetWire> = emptyList(),
) {
    val inProgress: Boolean get() = status == STATUS_IN_PROGRESS

    companion object {
        const val STATUS_IN_PROGRESS = "in_progress"
        const val STATUS_FINISHED = "finished"
        const val STATUS_CANCELLED = "cancelled"
    }
}

@Serializable
data class SetWire(
    val id: String = "",
    @SerialName("exercise_key") val exerciseKey: String,
    val name: String,
    val kind: String = "weighted",
    @SerialName("slot_key") val slotKey: String = "",
    @SerialName("set_number") val setNumber: Int,
    @SerialName("reps_low") val repsLow: Int,
    @SerialName("reps_high") val repsHigh: Int,
    val unit: String = "reps",
    @SerialName("target_weight_lbs") val targetWeightLbs: Double? = null,
    val rule: String = "",
    val words: String = "",
    val done: Boolean = false,
    val reps: Int? = null,
    @SerialName("weight_lbs") val weightLbs: Double? = null,
) {
    val weighted: Boolean get() = kind == "weighted"
    val extra: Boolean get() = rule == "extra"
}

/** Any workout write's reply: the session as the engine now holds it, and its words. */
@Serializable
data class SessionReplyWire(val session: SessionWire? = null, val words: String = "")
