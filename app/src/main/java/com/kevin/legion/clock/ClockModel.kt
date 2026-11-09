package com.kevin.legion.clock

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Alfred's clock (ADR 0079, map `.scratch/alfred-clock/`): everything the clock keeps, as plain
 * values. No Android here, so the math over it is unit tested on the JVM.
 *
 * Times are split by what they mean:
 * - an ALARM is a wall-clock time of day in the phone's zone ([hour], [minute], [date] for a
 *   one-off), so a zone change or a DST jump moves the instant, never the 7:00;
 * - a TIMER and the STOPWATCH are durations, measured on elapsed realtime within one boot
 *   ([bootCount]) and on the wall clock only across a reboot, where elapsed time restarts at 0.
 */
data class ClockAlarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    /** Empty = rings once. */
    val days: Set<DayOfWeek> = emptySet(),
    val label: String = "",
    val enabled: Boolean = true,
    /** A one-off's day. Null for a repeating alarm. */
    val date: LocalDate? = null,
    /** Wall-clock epoch ms of a pending snooze; it rings then instead of at its time. */
    val snoozedUntilMs: Long? = null,
) {
    val repeats: Boolean get() = days.isNotEmpty()
}

enum class TimerState { RUNNING, PAUSED, RINGING }

data class ClockTimer(
    val id: Int,
    val label: String,
    val durationMs: Long,
    val state: TimerState,
    /** What is left when [state] is PAUSED; otherwise derived from the end instants. */
    val remainingMs: Long,
    /** Elapsed-realtime end, valid only while [bootCount] is the phone's current boot. */
    val endsAtElapsed: Long = 0L,
    /** Wall-clock end, the fallback across a reboot. */
    val endsAtWallMs: Long = 0L,
    val bootCount: Int = 0,
)

data class Stopwatch(
    val running: Boolean = false,
    /** Time banked by earlier runs. */
    val accumulatedMs: Long = 0L,
    val startedAtElapsed: Long = 0L,
    val startedAtWallMs: Long = 0L,
    val bootCount: Int = 0,
    /** Total time on the watch at each lap, oldest first. */
    val laps: List<Long> = emptyList(),
)

/**
 * The bedtime schedule and tonight's state. [quietByBedtime] records whether bedtime ITSELF turned
 * Quiet on, so the morning turns off only that (ADR 0079). The feed block bedtime turns on stays on.
 */
data class Bedtime(
    val enabled: Boolean = false,
    val hour: Int = DEFAULT_HOUR,
    val minute: Int = DEFAULT_MINUTE,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val windDownMin: Int = DEFAULT_WIND_DOWN_MIN,
    val active: Boolean = false,
    val quietByBedtime: Boolean = false,
    /** Ended before the first unlock: Quiet's restore waits for unlock (its prefs are locked). */
    val pendingEnd: Boolean = false,
) {
    companion object {
        const val DEFAULT_HOUR = 22
        const val DEFAULT_MINUTE = 30
        const val DEFAULT_WIND_DOWN_MIN = 30
    }
}

data class ClockState(
    val alarms: List<ClockAlarm> = emptyList(),
    val timers: List<ClockTimer> = emptyList(),
    val stopwatch: Stopwatch = Stopwatch(),
    val bedtime: Bedtime = Bedtime(),
    val nextId: Int = 1,
)

/** The defaults chosen in the build (ADR 0079); Kevin may change them. */
object ClockDefaults {
    const val SNOOZE_MIN = 9
    const val AUTO_SILENCE_MIN = 10
    const val RAMP_SECONDS = 30
    const val TIMER_EXTRA_MIN = 1
    const val MINUTE_MS = 60_000L
    const val SECOND_MS = 1_000L
}
