package com.kevin.legion.service.tools.day

import java.time.LocalDate
import java.util.Locale
import org.json.JSONObject

/**
 * The day a conversation is about, carried to the next turn's clock-timed write that names no day
 * (2026-11-22). The voice session keeps it in a [ConversationDay]; [resolved] applies [carried] after
 * his own day word. Pure.
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
     * A clock-timed `manage_item` add or schedule put on today, in the turn right after one that was
     * about a LATER day, when his words name no day and no relative time: moved to that day, with the
     * note for the result; null when nothing changes. The confirmation still says the date.
     *
     * Why (2026-11-22): after a read of tomorrow, a follow-up add at a clock time with no day went on
     * today. Bounded by the review of 79c642b9: never a relative time ("in 20 minutes", "now",
     * "later", "tonight", "this evening"), never without a clock time, never into the past, and only
     * for the next turn ([ConversationDay]). Not gated on the turn's only date call, as
     * [RelativeDay.corrected] is: a refused first try and its retry are two calls of one turn.
     */
    @Suppress("ReturnCount") // not this tool or action, no clock time, no later day, not today, words
    fun carried(
        name: String,
        args: JSONObject,
        words: String?,
        turn: RelativeDay.TurnDates,
    ): Pair<JSONObject, String>? {
        if (name != "manage_item" || args.optString("action").trim().lowercase() !in CARRY_ACTIONS) return null
        if (args.optString("time").isBlank()) return null
        val about = turn.aboutDay?.takeIf { it.isAfter(turn.day) } ?: return null
        if (RelativeDay.parse(args.optString("date"), turn.day) != turn.day || !clockTimeOnly(words)) return null
        val fixed = JSONObject(args.toString()).put("date", about.toString())
        return fixed to "Used ${RelativeDay.label(about)}, the day this conversation is about; he named no day."
    }

    /**
     * True when [words] (his turn) give a clock time ("at seven", "7:00", "at noon") and no day and no
     * relative time: no today/tonight/tomorrow, weekday, month, holiday, ordinal, "next"/"week", no
     * "in", "now", "later", "soon", minutes or hours, and no part of a day ("this evening",
     * "in the morning"). Blank words are not.
     */
    fun clockTimeOnly(words: String?): Boolean {
        val w = words?.lowercase(Locale.US)?.split(Regex("[^a-z0-9]+"))?.filter { it.isNotEmpty() }.orEmpty()
        val clock = w.any { t -> t.any(Char::isDigit) || t in CLOCK_WORDS || t in CLOCK_NAMES }
        val dayOrRelative = w.any {
            it in DAY_NAMES || it in RELATIVE || it in PARTS_OF_DAY || ORDINAL.matches(it) ||
                it in RelativeDay.BLOCKERS && it !in CLOCK_WORDS
        }
        return clock && !dayOrRelative
    }

    /**
     * The day a successful call leaves for the NEXT turn: a one-day calendar read or a dated add or
     * schedule sets it, a calendar read of a range clears it, anything else keeps [current]. [args]
     * are the ones the call ran with (after [resolved]).
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
            "manage_item" -> if (args.optString("action").trim().lowercase() in CARRY_ACTIONS) {
                RelativeDay.parse(args.optString("date"), today) ?: current
            } else {
                current
            }
            else -> current
        }
    }

    private val CARRY_ACTIONS = setOf("add", "schedule")
    private val CLOCK_WORDS = setOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "fifteen", "twenty", "thirty", "forty", "fifty",
    )
    private val CLOCK_NAMES = setOf("noon", "midnight", "oclock", "pm", "am")
    private val DAY_NAMES = setOf("today", "tonight", "tomorrow", "yesterday", "day", "days", "date", "tmrw", "tmr")
    private val RELATIVE = setOf(
        "in", "now", "later", "soon", "minute", "minutes", "min", "mins", "hour", "hours", "hr", "hrs",
        "couple", "few", "moment", "while",
    )
    private val PARTS_OF_DAY = setOf("morning", "afternoon", "evening", "night", "tonight")
    private val ORDINAL = Regex("[0-9]+(st|nd|rd|th)")
}

/**
 * The carried day's lifetime (review of 79c642b9): what one turn's calls settle ([note]) is offered to
 * the NEXT turn only ([startTurn]), and to all of that turn's calls, its retries included ([day]).
 * "Add the dentist Friday", then later "remind me at six" stays today.
 */
internal class ConversationDay {
    /** The day this turn's calls may carry to; null for none. */
    @Volatile var day: java.time.LocalDate? = null
        private set
    @Volatile private var next: java.time.LocalDate? = null

    /** A new turn of his starts: the last turn's day becomes this one's, and nothing more is kept. */
    fun startTurn() {
        day = next
        next = null
    }

    /** A call of this turn came back: what it leaves for the next turn ([DateCarry.nextAboutDay]). */
    fun note(name: String, args: JSONObject, ok: Boolean, today: java.time.LocalDate) {
        next = DateCarry.nextAboutDay(next, name, args, ok, today)
    }
}
