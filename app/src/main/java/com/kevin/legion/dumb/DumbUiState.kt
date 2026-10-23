package com.kevin.legion.dumb

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The Leave screen's numbers. [remainingSeconds] rounds UP so the last second reads 1, never 0 while waiting. */
data class LeaveUi(
    val waiting: Boolean,
    val remainingSeconds: Int,
    /** 0f at the tap, 1f when the wait is over. */
    val progress: Float,
)

/** Everything Home A and the Leave screen draw. Pure data, built by [DumbUiStates.build]. */
data class DumbUiState(
    val on: Boolean = false,
    val clock: String = "",
    val date: String = "",
    /** "2h 14m", the length of the current run. */
    val elapsed: String = "",
    /** The line under the date: "in the matrix for 2h 14m", plus " · 4 days in a row" once there is a streak. */
    val statusLine: String = "",
    val entries: List<KeptEntry> = KeptEntry.entries,
    /** "Mia, Mom, Dad get through", or words saying nobody is chosen. */
    val peopleLine: String = "",
    /** Non-null while a leave is pending; the screen shows the Leave flow instead of the list. */
    val leave: LeaveUi? = null,
)

object DumbFormat {
    private const val MS_PER_MINUTE = 60_000L
    private const val MINUTES_PER_HOUR = 60L
    private const val MS_PER_SECOND = 1_000L

    /** Whole seconds, rounded UP, so the last second of a wait reads 1 and never 0 while waiting. */
    fun secondsCeil(ms: Long): Int = ((ms.coerceAtLeast(0L) + MS_PER_SECOND - 1) / MS_PER_SECOND).toInt()

    /** "2h 14m", or "14m" under an hour. Under a minute still reads "0m": the counter has begun. */
    fun elapsed(ms: Long): String {
        val minutes = (ms.coerceAtLeast(0L) / MS_PER_MINUTE)
        val h = minutes / MINUTES_PER_HOUR
        val m = minutes % MINUTES_PER_HOUR
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    /** The prototype's footer: "Mia, Mom, Dad get through". Plural verb only for more than one name. */
    fun statusLine(elapsed: String, streakDays: Int): String = when (streakDays) {
        0 -> "in the matrix for $elapsed"
        1 -> "in the matrix for $elapsed \u00b7 1 day in a row"
        else -> "in the matrix for $elapsed \u00b7 $streakDays days in a row"
    }

    fun peopleLine(names: List<String>): String = when (names.size) {
        0 -> "No one chosen yet. Pick who gets through in settings."
        1 -> "${names[0]} gets through"
        else -> names.joinToString(", ") + " get through"
    }
}

object DumbUiStates {
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")

    fun build(
        state: DumbModeState,
        nowMs: Long,
        zone: ZoneId,
        locale: Locale,
        peopleNames: List<String>,
        streakDays: Int = 0,
    ): DumbUiState {
        val at = Instant.ofEpochMilli(nowMs).atZone(zone)
        val requested = state.leaveRequestedAtMs
        val elapsed = if (state.on) DumbFormat.elapsed(nowMs - state.startedAtMs) else ""
        val leave = if (state.on && requested != null) {
            val remaining = DumbLeaveRule.remainingMs(requested, nowMs)
            LeaveUi(
                waiting = remaining > 0L,
                remainingSeconds = DumbFormat.secondsCeil(remaining),
                progress = 1f - remaining.toFloat() / DumbLeaveRule.WAIT_MS,
            )
        } else {
            null
        }
        return DumbUiState(
            on = state.on,
            clock = CLOCK.withLocale(locale).format(at),
            date = DATE.withLocale(locale).format(at),
            elapsed = elapsed,
            statusLine = if (state.on) DumbFormat.statusLine(elapsed, streakDays) else "",
            peopleLine = DumbFormat.peopleLine(peopleNames),
            leave = leave,
        )
    }
}
