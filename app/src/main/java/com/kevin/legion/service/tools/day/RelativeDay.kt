package com.kevin.legion.service.tools.day

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
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
    fun saidDay(words: String?, today: LocalDate, at: LocalTime? = null): LocalDate? {
        val w = words?.lowercase(Locale.US)?.split(Regex("[^a-z0-9]+"))?.filter { it.isNotEmpty() }.orEmpty()
        if (w.isEmpty() || w.any { it in BLOCKERS || it.any(Char::isDigit) }) return null
        // "tonight" in the small hours may mean the night in progress, which began on yesterday's date.
        if (at != null && at.hour < SMALL_HOURS_END && "tonight" in w) return null
        val days = w.mapNotNull { DAY_WORDS[it] }
        return days.singleOrNull()?.let { today.plusDays(it) }
    }

    /**
     * The turn a tool call belongs to, carried in the call's coroutine context by the voice session:
     * [day] is the date the model was given (the prompt's, not the clock when the tool runs, 2026-11-18
     * review: a call landing just after midnight moved a right "tomorrow" a day on), [startedAt] the
     * turn's local time, and [onlyDateCall] whether this is the turn's only date-taking call so far.
     */
    class TurnDates(val day: LocalDate, val startedAt: LocalTime, val onlyDateCall: Boolean) :
        AbstractCoroutineContextElement(TurnDates) {
        companion object Key : CoroutineContext.Key<TurnDates>
    }

    /** The tools the fix is for: the calendar read and the task and reminder-item writes. */
    val DATE_TOOLS: Set<String> = setOf("read_calendar", "complete_task", "manage_item")

    /** A call that takes a date ([DATE_TOOLS] with a date argument), counted per turn for [TurnDates]. */
    fun isDateCall(name: String, args: JSONObject): Boolean = name in DATE_TOOLS && DATE_KEYS.any { args.has(it) }

    /**
     * [args] with its one date, one day off the day he [words] said, put back to that day, and the
     * note for the result; null when nothing changes. Only a [DATE_TOOLS] call, only when it is the
     * turn's only date-taking call, only one day (`date`, or `from` and `to` the same `yyyy-MM-dd`;
     * never a range or one side of one), and only exactly one day off (2026-11-18 review C, D).
     */
    @Suppress("ReturnCount") // not this tool, not the only call, nothing said, no single day, not one off
    fun corrected(name: String, args: JSONObject, words: String?, turn: TurnDates): Pair<JSONObject, String>? {
        if (name !in DATE_TOOLS || !turn.onlyDateCall) return null
        val said = saidDay(words, turn.day, turn.startedAt) ?: return null
        val (keys, asked) = singleDay(args) ?: return null
        if (kotlin.math.abs(asked.toEpochDay() - said.toEpochDay()) != 1L) return null
        val fixed = JSONObject(args.toString())
        for (k in keys) fixed.put(k, said.toString())
        return fixed to "Used ${label(said)}, the day he said, not the date asked for."
    }

    /** The keys holding [args]' one day and that day; null for a range, one side of one, or no ISO day. */
    private fun singleDay(args: JSONObject): Pair<List<String>, LocalDate>? {
        fun iso(k: String) = runCatching { LocalDate.parse(args.optString(k).trim()) }.getOrNull()
        val ranged = args.has("from") || args.has("to")
        return when {
            ranged && args.has("date") -> null
            ranged -> iso("from")?.takeIf { it == iso("to") }?.let { listOf("from", "to") to it }
            else -> iso("date")?.let { listOf("date") to it }
        }
    }

    private const val SMALL_HOURS_END = 5
    private val DATE_KEYS = listOf("date", "from", "to")
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
