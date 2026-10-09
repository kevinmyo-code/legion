package com.kevin.legion.calendar

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

/**
 * The one place an all-day row's date is recovered, so no speaker or scheduler re-derives it.
 *
 * An all-day `Event.startsAt` is **UTC midnight of its calendar date** (Android's own all-day
 * convention, documented on `data/local/Event.kt`'s `activeByKindInLocalWindow`). Read as an instant
 * in the device zone it is the evening BEFORE: Oct 6 all-day = 2026-10-05 19:00 in Chicago. On
 * 2026-10-05 that produced the opener line "paddleboard purchase scheduled for seven this evening"
 * and a `dates_reminder` raise "came due 7:00 PM", both a day early (voice audit finding 3).
 *
 * The rule everything here enforces: an all-day item has a DATE and never a clock time.
 */
object AllDayTime {

    /** Local hour an all-day reminder fires on its date. No convention existed (the notes alarm
     * scheduler arms a list item at its own `startsAt`, which for an all-day item is local
     * midnight); 9 AM was chosen as a waking hour that is still early enough to act on. */
    const val REMINDER_HOUR = 9

    /** The calendar date an all-day row names - read in UTC, never the device zone. */
    fun localDate(startsAt: Long): LocalDate =
        Instant.ofEpochMilli(startsAt).atZone(ZoneOffset.UTC).toLocalDate()

    /** Start of the item's date in [zone] for an all-day row; the raw instant for a timed one. */
    fun anchorMs(startsAt: Long, allDay: Boolean, zone: ZoneId): Long =
        if (allDay) localDate(startsAt).atStartOfDay(zone).toInstant().toEpochMilli() else startsAt

    /** When a reminder for the row should fire: [REMINDER_HOUR] local on its date for all-day,
     * the raw instant for a timed row. */
    fun reminderMs(startsAt: Long, allDay: Boolean, zone: ZoneId): Long =
        if (allDay) localDate(startsAt).atTime(REMINDER_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        else startsAt

    /** Start of the day AFTER an all-day item's date, in [zone]; end of its span. */
    fun dayEndMs(anchorMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(anchorMs).atZone(zone).toLocalDate().plusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * "today", "tomorrow", a weekday within the coming week, else weekday plus month and day.
     * [date] is the item's calendar date, [today] the device's local date. Never carries a time.
     */
    fun dateWords(date: LocalDate, today: LocalDate): String {
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, date)
        return when {
            days == 0L -> "today"
            days == 1L -> "tomorrow"
            days == -1L -> "yesterday"
            days in 2..6 -> "on ${weekday(date.dayOfWeek)}"
            else -> "on ${weekday(date.dayOfWeek)} ${date.month.getDisplayName(TextStyle.SHORT, Locale.US)} ${date.dayOfMonth}"
        }
    }

    private fun weekday(d: DayOfWeek): String = d.getDisplayName(TextStyle.FULL, Locale.US)
}
