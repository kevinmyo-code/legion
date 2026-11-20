package com.kevin.legion.service.tools.day

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * A date range said as words, resolved by CODE on the phone's local date (2026-11-19): the model
 * passes `this_week` and never works the dates out. The audio-first eval read "what's due this week"
 * as three different windows (6 of 6 right from text, 3 of 6 from audio); with the dates computed
 * here, which path heard him no longer matters.
 *
 * The week ends on Sunday: `this_week` is today through this Sunday; `next_week` is next Monday
 * through the Sunday after; `this_weekend` is this Saturday and Sunday (today and Sunday on a
 * Sunday). `overdue` reads today, whose `due` block carries everything overdue
 * ([com.kevin.legion.calendar.DueReader]). A plain date or a day word ([RelativeDay]) still works for
 * a spoken date; a named range sent beside dates wins. Pure: no clock, no Android.
 */
internal object NamedRange {
    /** The window read; [name] is the named range, or null for dates as sent. */
    data class Window(val name: String?, val from: LocalDate, val to: LocalDate)

    val NAMES = listOf("today", "tomorrow", "this_week", "next_7_days", "this_weekend", "next_week", "overdue")

    fun resolve(raw: String, today: LocalDate): Window? {
        val name = raw.trim().lowercase(Locale.US).replace(' ', '_').replace("seven", "7")
        val sunday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        return when (name) {
            "today" -> Window(name, today, today)
            "tomorrow" -> Window(name, today.plusDays(1), today.plusDays(1))
            "this_week" -> Window(name, today, sunday)
            "next_7_days" -> Window(name, today, today.plusDays(SIX_DAYS))
            "this_weekend" -> Window(name, maxOf(today, sunday.minusDays(1)), sunday)
            "next_week" -> Window(name, sunday.plusDays(1), sunday.plusDays(WEEK_DAYS))
            "overdue" -> Window(name, today, today)
            else -> null
        }
    }

    /**
     * The window for `from`/`to` as the model sent them: a named range in either wins, else the
     * dates. Null: unreadable.
     */
    fun window(from: String, to: String, today: LocalDate): Window? {
        val named = resolve(from, today) ?: resolve(to, today)?.takeIf { RelativeDay.parse(from, today) == null }
        if (named != null) return named
        val f = RelativeDay.parse(from, today)
        val t = f?.let { RelativeDay.parse(to.ifBlank { from }, today) }
        return if (f == null || t == null || t.isBefore(f)) null else Window(null, f, t)
    }

    private const val SIX_DAYS = 6L
    private const val WEEK_DAYS = 7L
}
