package com.kevin.legion.service.tools.day

import java.time.LocalDate
import java.util.Locale
import org.json.JSONObject

/**
 * The day a conversation is about, carried to a follow-up write that names none (2026-11-22). The
 * voice session keeps the day ([nextAboutDay]); [resolved] applies [carried] after his
 * own day word. Pure.
 */
internal object DateCarry {
    /** The date fixes, in order: his own day word ([RelativeDay.corrected]), then the conversation's day. */
    fun resolved(
        name: String,
        args: JSONObject,
        words: String?,
        turn: RelativeDay.TurnDates,
    ): Pair<JSONObject, String>? =
        RelativeDay.corrected(name, args, words, turn) ?: carried(name, args, words, turn)

    /**
     * A dated `manage_item` write put on today while the conversation is about another day and his
     * words name no day at all, moved to the conversation's day, with the note for the result; null
     * when nothing changes.
     *
     * Why (2026-11-22): after a read of tomorrow, "put dinner with <a name> at seven" was added for
     * today. A follow-up that names no day is about the day being talked about. Not gated on the
     * turn's only date call, as [RelativeDay.corrected] is: there the model's refused first try and its retry are
     * two calls of one turn, and both carry the same wrong day.
     */
    @Suppress("ReturnCount") // not this tool or action, no other day, not today, a day named
    fun carried(
        name: String,
        args: JSONObject,
        words: String?,
        turn: RelativeDay.TurnDates,
    ): Pair<JSONObject, String>? {
        if (name != "manage_item" || args.optString("action").trim().lowercase() !in CARRY_ACTIONS) return null
        val about = turn.aboutDay?.takeIf { it != turn.day } ?: return null
        if (RelativeDay.parse(args.optString("date"), turn.day) != turn.day || !namesNoDay(words)) return null
        val fixed = JSONObject(args.toString()).put("date", about.toString())
        return fixed to "Used ${RelativeDay.label(about)}, the day this conversation is about; he named no day."
    }

    /**
     * True when [words] (his turn) hold no day at all: no today/tomorrow/yesterday, weekday, month,
     * holiday, ordinal ("the 23rd", "twenty third"), "next"/"last"/"week", or "this morning"-style
     * part of a day. A clock time ("at seven", "7:00") is not a day. Blank words are not "no day".
     */
    fun namesNoDay(words: String?): Boolean {
        val w = words?.lowercase(Locale.US)?.split(Regex("[^a-z0-9]+"))?.filter { it.isNotEmpty() }.orEmpty()
        if (w.isEmpty()) return false
        val dayWord = w.any {
            it in DAY_NAMES || ORDINAL.matches(it) || it in RelativeDay.BLOCKERS && it !in CLOCK_WORDS
        }
        return !dayWord && w.zipWithNext().none { (a, b) -> a == "this" && b in PARTS_OF_DAY }
    }

    /**
     * The conversation's day after a call that came back [ok]: a one-day calendar read or a dated
     * write sets it, a calendar read of a range clears it, anything else keeps [current]. [args] are
     * the ones the call ran with (after [resolved]).
     */
    fun nextAboutDay(current: LocalDate?, name: String, args: JSONObject, ok: Boolean, today: LocalDate): LocalDate? {
        if (!ok) return current
        return when (name) {
            "read_calendar" -> {
                val from = args.optString("from").trim()
                val to = args.optString("to").trim().ifBlank { from }
                val day = RelativeDay.parse(from, today)
                if (from.isBlank()) today else day?.takeIf { it == RelativeDay.parse(to, today) }
            }
            "manage_item" -> RelativeDay.parse(args.optString("date"), today) ?: current
            else -> current
        }
    }

    private val CARRY_ACTIONS = setOf("add", "schedule")
    private val CLOCK_WORDS = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
        "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
        "thirty", "couple", "few", "not", "from", "after", "before", "move", "moved", "push",
    )
    private val DAY_NAMES = setOf("today", "tonight", "tomorrow", "yesterday", "day", "days", "date", "tmrw", "tmr")
    private val PARTS_OF_DAY = setOf("morning", "afternoon", "evening", "night", "noon")
    private val ORDINAL = Regex("[0-9]+(st|nd|rd|th)")
}
