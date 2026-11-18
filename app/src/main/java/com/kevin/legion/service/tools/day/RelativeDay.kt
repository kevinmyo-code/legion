package com.kevin.legion.service.tools.day

import org.json.JSONObject
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
     * The one day [words] (his turn) names, or null: exactly one of today, tonight or tomorrow, and
     * no other date expression anywhere in the turn (no "not", no number or ordinal, no month, weekday
     * or holiday, no "from", "after", "next", "week", "fortnight", no moving a date). "and tomorrow?"
     * is tomorrow; "tonight" is today; "not tomorrow, the twenty first" is null.
     *
     * Why (2026-11-17 evening eval, and 2026-11-15 before it): asked "and tomorrow?" after a calendar
     * answer, flash-lite passed today+2 in the evening, though the preamble spells out tomorrow's date.
     */
    fun saidDay(words: String?, today: LocalDate): LocalDate? {
        val w = words?.lowercase(Locale.US)?.split(Regex("[^a-z0-9]+"))?.filter { it.isNotEmpty() }.orEmpty()
        if (w.isEmpty() || w.any { it in BLOCKERS || it.any(Char::isDigit) }) return null
        val days = w.mapNotNull { DAY_WORDS[it] }
        return days.singleOrNull()?.let { today.plusDays(it) }
    }

    /**
     * [args] with a date the model worked out one day off the day he [said] put back to that day, and
     * the note for the result; null when nothing changes. Only `yyyy-MM-dd` values in [DATE_KEYS]
     * move, only when exactly one day off, and a range (`from` before `to`) never moves: every tool
     * that takes a date, reads and writes alike, goes through this once, at dispatch.
     */
    @Suppress("ReturnCount") // nothing said, a range, or nothing one day off: each keeps the args
    fun corrected(args: JSONObject, words: String?, today: LocalDate): Pair<JSONObject, String>? {
        val said = saidDay(words, today) ?: return null
        val dates = DATE_KEYS.filter { args.has(it) }
            .associateWith { runCatching { LocalDate.parse(args.optString(it).trim()) }.getOrNull() }
        val from = dates["from"]
        val to = dates["to"]
        if (from != null && to != null && from != to) return null
        val off = dates.filterValues { it != null && kotlin.math.abs(it.toEpochDay() - said.toEpochDay()) == 1L }
        if (off.isEmpty()) return null
        val fixed = JSONObject(args.toString())
        for (k in off.keys) fixed.put(k, said.toString())
        return fixed to "Used ${label(said)}, the day he said, not the date asked for."
    }

    private val DATE_KEYS = listOf("date", "day", "from", "to")
    private val DAY_WORDS = mapOf("today" to 0L, "tonight" to 0L, "tomorrow" to 1L)
    private val BLOCKERS = setOf(
        "not", "from", "after", "before", "next", "last", "week", "weekend", "fortnight", "month", "yesterday",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "january", "february",
        "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
        "thanksgiving", "christmas", "easter", "halloween", "holiday", "birthday", "eve",
        "move", "moved", "reschedule", "push", "postpone",
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
        "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
        "thirty", "couple", "few", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth",
        "ninth", "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth",
        "seventeenth", "eighteenth", "nineteenth", "twentieth", "thirtieth",
    )

    /** "Monday 2026-11-16": how a resolved day is said back in a result. */
    fun label(day: LocalDate): String = "${day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)} $day"
}
