package com.kevin.legion.clock

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * The clock's arithmetic, pure (ADR 0079). Every instant is wall-clock epoch ms; every time of day
 * is resolved in the zone passed in, never cached, so a zone change re-resolves the same 7:00.
 *
 * **DST:** [ZonedDateTime.of] decides. A time inside the spring-forward gap moves later by the gap
 * (02:30 that does not exist rings at 03:30); a time inside the fall-back overlap rings once, at
 * the earlier offset.
 */
object AlarmMath {

    private const val DAYS_TO_SEARCH = 8L
    private const val HOURS_PER_HALF_DAY = 12
    private const val SECONDS_PER_MINUTE = 60L
    private const val MINUTES_PER_HOUR = 60L

    val WEEKDAYS: Set<DayOfWeek> =
        setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    val WEEKEND: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    val EVERY_DAY: Set<DayOfWeek> = DayOfWeek.entries.toSet()

    /** The instant [alarm] rings next, or null when it is off. A one-off's instant may be past. */
    fun nextTrigger(alarm: ClockAlarm, nowMs: Long, zone: ZoneId): Long? {
        if (!alarm.enabled) return null
        alarm.snoozedUntilMs?.let { if (it > nowMs) return it }
        val time = LocalTime.of(alarm.hour, alarm.minute)
        alarm.date?.let { return at(it, time, zone) }
        if (!alarm.repeats) return null
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return (0 until DAYS_TO_SEARCH)
            .map { today.plusDays(it) }
            .filter { it.dayOfWeek in alarm.days }
            .map { at(it, time, zone) }
            .firstOrNull { it > nowMs }
    }

    /** A one-off's day: today if [hour]:[minute] is still ahead, else tomorrow. */
    fun oneOffDate(hour: Int, minute: Int, nowMs: Long, zone: ZoneId): LocalDate {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return if (at(today, LocalTime.of(hour, minute), zone) > nowMs) today else today.plusDays(1)
    }

    /** The next instant [hour]:[minute] falls on one of [days], strictly after [nowMs]. */
    fun nextOnDays(hour: Int, minute: Int, days: Set<DayOfWeek>, nowMs: Long, zone: ZoneId): Long? =
        nextTrigger(ClockAlarm(id = 0, hour = hour, minute = minute, days = days), nowMs, zone)

    fun at(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
        ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()

    /**
     * "07:30", "7:30", "7", "7am", "6:45 pm", "1830". Null when it is not a time of day. Spoken
     * times reach the tool as the model writes them, so a little leniency saves a failed call.
     */
    @Suppress("ReturnCount", "MagicNumber") // one exit per shape; 12/24/100 are clock arithmetic
    fun parseTime(raw: String): Pair<Int, Int>? {
        val s = raw.trim().lowercase(Locale.US).replace(".", "")
        val m = Regex("""^(\d{1,2})(?::?(\d{2}))?\s*(am|pm)?$""").matchEntire(s) ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        val half = m.groupValues[3]
        if (minute > 59) return null
        if (half.isNotEmpty()) {
            if (hour !in 1..HOURS_PER_HALF_DAY) return null
            hour = hour % HOURS_PER_HALF_DAY + if (half == "pm") HOURS_PER_HALF_DAY else 0
        }
        if (hour > 23) return null
        return hour to minute
    }

    /** "weekdays", "weekends", "daily", "every day", "mon,wed,fri", "once" or blank (= once). Null if unreadable. */
    fun parseDays(raw: String?): Set<DayOfWeek>? {
        val s = raw?.trim()?.lowercase(Locale.US).orEmpty()
        return when (s) {
            "", "once", "none", "one time" -> emptySet()
            "weekdays", "weekday", "workdays" -> WEEKDAYS
            "weekends", "weekend" -> WEEKEND
            "daily", "every day", "everyday", "all" -> EVERY_DAY
            else -> s.split(',', ' ', '/').filter { it.isNotBlank() }.map { dayOf(it) ?: return null }.toSet()
        }
    }

    private fun dayOf(token: String): DayOfWeek? =
        DayOfWeek.entries.firstOrNull { it.name.lowercase(Locale.US).startsWith(token.take(3)) && token.length >= 2 }

    /** "weekdays", "every day", "Mon, Wed", or "once". */
    fun daysLabel(days: Set<DayOfWeek>): String = when (days) {
        emptySet<DayOfWeek>() -> "once"
        EVERY_DAY -> "every day"
        WEEKDAYS -> "weekdays"
        WEEKEND -> "weekends"
        else -> days.sorted().joinToString(", ") { it.name.take(3).lowercase(Locale.US).replaceFirstChar(Char::uppercaseChar) }
    }

    /** "07:30". The clock's own 24-hour form, which is what the model sends back. */
    fun hhmm(hour: Int, minute: Int): String = String.format(Locale.US, "%02d:%02d", hour, minute)

    /** Time left on [timer]: elapsed realtime within its boot, the wall clock across a reboot. */
    fun timerRemaining(timer: ClockTimer, nowElapsed: Long, nowWallMs: Long, bootCount: Int): Long = when (timer.state) {
        TimerState.PAUSED -> timer.remainingMs
        TimerState.RINGING -> 0L
        TimerState.RUNNING ->
            if (timer.bootCount == bootCount) timer.endsAtElapsed - nowElapsed else timer.endsAtWallMs - nowWallMs
    }.coerceAtLeast(0L)

    /** Total on the stopwatch now. Across a reboot the running stretch is measured on the wall clock. */
    fun stopwatchTotal(sw: Stopwatch, nowElapsed: Long, nowWallMs: Long, bootCount: Int): Long {
        if (!sw.running) return sw.accumulatedMs
        val run = if (sw.bootCount == bootCount) nowElapsed - sw.startedAtElapsed else nowWallMs - sw.startedAtWallMs
        return sw.accumulatedMs + run.coerceAtLeast(0L)
    }

    /** "4 minutes 12 seconds", "1 hour 5 minutes", "30 seconds". Rounded up to the second. */
    fun spokenDuration(ms: Long): String {
        val total = (ms + ClockDefaults.SECOND_MS - 1) / ClockDefaults.SECOND_MS
        val h = total / (SECONDS_PER_MINUTE * MINUTES_PER_HOUR)
        val m = total / SECONDS_PER_MINUTE % MINUTES_PER_HOUR
        val s = total % SECONDS_PER_MINUTE
        val parts = listOfNotNull(
            unit(h, "hour"),
            unit(m, "minute"),
            unit(s, "second").takeIf { h == 0L },
        )
        return parts.joinToString(" ").ifEmpty { "0 seconds" }
    }

    private fun unit(n: Long, word: String): String? = if (n == 0L) null else "$n $word" + if (n == 1L) "" else "s"

    /** "4:12" or "1:05:09", for the screen. */
    fun digital(ms: Long): String {
        val total = ms.coerceAtLeast(0L) / ClockDefaults.SECOND_MS
        val h = total / (SECONDS_PER_MINUTE * MINUTES_PER_HOUR)
        val m = total / SECONDS_PER_MINUTE % MINUTES_PER_HOUR
        val s = total % SECONDS_PER_MINUTE
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }
}
