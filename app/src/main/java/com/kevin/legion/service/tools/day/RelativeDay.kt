package com.kevin.legion.service.tools.day

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * A date argument as the model may send it: `yyyy-MM-dd`, or the word the user said, `today`,
 * `tomorrow` or `yesterday`, resolved here on the phone's own LOCAL date (2026-11-16).
 *
 * Why: in the 2026-11-15 voice eval, flash-lite answered a follow-up "and tomorrow?" by asking for
 * today+2, in every evening run, even with today, tomorrow and yesterday spelled out as local dates
 * in the preamble; single-turn "tomorrow" was always right. The model doing date arithmetic is the
 * failure, so the arithmetic moves into Kotlin: the tool hint tells it to pass the word.
 */
internal object RelativeDay {
    /** [raw] as a date, or null when it is neither a known word nor `yyyy-MM-dd`. */
    fun parse(raw: String, today: LocalDate): LocalDate? = when (raw.trim().lowercase(Locale.US)) {
        "today" -> today
        "tomorrow" -> today.plusDays(1)
        "yesterday" -> today.minusDays(1)
        else -> runCatching { LocalDate.parse(raw.trim()) }.getOrNull()
    }

    fun today(zone: ZoneId = ZoneId.systemDefault()): LocalDate = LocalDate.now(zone)

    /**
     * The one day [words] (his turn) names with a plain day word, or null. "and tomorrow?" is
     * tomorrow; "tonight" is today. Anything that could name another day as well (two day words, a
     * weekday, a month, a number, "after", "next", "week") is null: the model's dates stand then.
     *
     * Why (2026-11-17 evening eval, and 2026-11-15 before it): asked "and tomorrow?" after a calendar
     * answer, flash-lite still passed today+2 as a date in the evening, though the tool asks for the word
     * and the preamble spells out tomorrow's date. His own word is the fact; the date was arithmetic.
     */
    fun saidDay(words: String?, today: LocalDate): LocalDate? {
        val w = words?.lowercase(Locale.US)?.split(Regex("[^a-z0-9]+"))?.filter { it.isNotEmpty() }.orEmpty()
        if (w.isEmpty() || w.any { it in BLOCKERS || it.any(Char::isDigit) }) return null
        val days = w.mapNotNull { DAY_WORDS[it] }.toSet()
        return days.singleOrNull()?.let { today.plusDays(it) }
    }

    /**
     * The window to read when the model asked for the one day [fromDate]..[toDate] but he said
     * [said]: that day, or null to keep the model's (a range, the right day, or nothing said).
     */
    fun correct(fromDate: LocalDate, toDate: LocalDate, said: LocalDate?): Pair<LocalDate, LocalDate>? =
        if (said != null && fromDate == toDate && fromDate != said) said to said else null

    private val DAY_WORDS = mapOf("today" to 0L, "tonight" to 0L, "tomorrow" to 1L, "yesterday" to -1L)
    private val BLOCKERS = setOf(
        "after", "before", "next", "last", "week", "weekend", "month", "monday", "tuesday", "wednesday",
        "thursday", "friday", "saturday", "sunday", "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
    )

    /** "Monday 2026-11-16": how a resolved day is said back in a result. */
    fun label(day: LocalDate): String = "${day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)} $day"
}
