package com.kevin.legion.ui.clock

import com.kevin.legion.clock.AlarmMath
import com.kevin.legion.clock.AlarmText
import com.kevin.legion.clock.ClockAlarm
import com.kevin.legion.clock.ClockState
import com.kevin.legion.clock.ClockTime
import com.kevin.legion.clock.TimerState
import java.time.DayOfWeek

/** One alarm as the list shows it. */
data class AlarmRow(val alarm: ClockAlarm, val time: String, val detail: String)

data class TimerRow(val id: Int, val label: String, val left: String, val paused: Boolean, val ringing: Boolean)

data class StopwatchRow(val total: String, val running: Boolean, val laps: List<String>, val started: Boolean)

data class BedtimeRow(
    val enabled: Boolean,
    val hour: Int,
    val minute: Int,
    val days: Set<DayOfWeek>,
    val active: Boolean,
    val windDownMin: Int,
)

/** A fixable problem in words, with the button that opens the system page. */
data class ProblemRow(val sentence: String, val button: String, val key: String)

/** Everything the Clock screen draws, computed from [ClockState] at one instant. */
data class ClockUi(
    val tab: Int = 0,
    val alarms: List<AlarmRow> = emptyList(),
    val timers: List<TimerRow> = emptyList(),
    val stopwatch: StopwatchRow = StopwatchRow("0:00", running = false, laps = emptyList(), started = false),
    val bedtime: BedtimeRow = BedtimeRow(false, 22, 30, AlarmMath.EVERY_DAY, active = false, windDownMin = 30),
    val problems: List<ProblemRow> = emptyList(),
    val bedtimeProblem: ProblemRow? = null,
    val notice: String? = null,
) {
    companion object {
        fun from(
            state: ClockState,
            time: ClockTime,
            tab: Int,
            problems: List<ProblemRow>,
            bedtimeProblem: ProblemRow?,
            notice: String?,
        ): ClockUi {
            val now = time.nowMs()
            val zone = time.zone()
            val elapsed = time.elapsedMs()
            val boot = time.bootCount()
            val alarms = state.alarms.sortedBy { it.hour * 60 + it.minute }.map { a ->
                val at = AlarmMath.nextTrigger(a, now, zone)
                val next = if (at != null && at > now) "Rings ${AlarmText.whenAt(at, now, zone)}" else "Off"
                val days = AlarmMath.daysLabel(a.days).replaceFirstChar(Char::uppercaseChar)
                AlarmRow(a, AlarmMath.hhmm(a.hour, a.minute), listOfNotNull(a.label.ifBlank { null }, days, next).joinToString(" · "))
            }
            val timers = state.timers.map { t ->
                TimerRow(t.id, t.label, AlarmMath.digital(AlarmMath.timerRemaining(t, elapsed, now, boot)),
                    paused = t.state == TimerState.PAUSED, ringing = t.state == TimerState.RINGING)
            }
            val sw = state.stopwatch
            val total = AlarmMath.stopwatchTotal(sw, elapsed, now, boot)
            val laps = sw.laps.mapIndexed { i, at ->
                "Lap ${i + 1}  ${AlarmMath.digital(at - (sw.laps.getOrNull(i - 1) ?: 0L))}  ·  ${AlarmMath.digital(at)}"
            }.reversed()
            val b = state.bedtime
            return ClockUi(
                tab = tab,
                alarms = alarms,
                timers = timers,
                stopwatch = StopwatchRow(AlarmMath.digital(total), sw.running, laps, started = total > 0 || sw.running),
                bedtime = BedtimeRow(b.enabled, b.hour, b.minute, b.days, b.active, b.windDownMin),
                problems = problems,
                bedtimeProblem = bedtimeProblem,
                notice = notice,
            )
        }
    }
}

/** What the Clock screen can ask for. Every one calls the same [com.kevin.legion.clock.ClockController] the voice tool does. */
data class ClockActions(
    val onTab: (Int) -> Unit = {},
    val onSaveAlarm: (ClockAlarm) -> Unit = {},
    val onToggleAlarm: (Int, Boolean) -> Unit = { _, _ -> },
    val onDeleteAlarm: (Int) -> Unit = {},
    val onStartTimer: (Long, String) -> Unit = { _, _ -> },
    val onPauseTimer: (Int) -> Unit = {},
    val onResumeTimer: (Int) -> Unit = {},
    val onCancelTimer: (Int) -> Unit = {},
    val onStopwatch: (StopwatchVerb) -> Unit = {},
    val onSaveBedtime: (Boolean, Int, Int, Set<DayOfWeek>) -> Unit = { _, _, _, _ -> },
    val onBedtimeNow: (Boolean) -> Unit = {},
    val onFix: (String) -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    val onClose: () -> Unit = {},
)

enum class StopwatchVerb { START, STOP, LAP, RESET }
