package com.kevin.legion.service.cascade

import com.kevin.legion.greeting.CachedPack
import com.kevin.legion.greeting.GreetingSource
import com.kevin.legion.greeting.SourceState
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Today's agenda in a spoken conversation's context (2026-11-16, voice quick wins item C), so "what
 * do I have today" and "what's due" are answered in one model round with no tool call (6 of 12
 * cloud tool turns measured that morning were `day`, ~1.1 s each for round 1 plus the tool).
 *
 * Read from the greeting's facts pack the phone already keeps
 * ([com.kevin.legion.greeting.GreetingPackCache]); a fetch already in flight is waited for briefly.
 * **A brief-authorised exception to CLAUDE.md section 7's "pull-based tools always"**: the block
 * carries only what the pack holds, says when it was read, and sends everything else (other days,
 * overdue work, weather, every write) back to the tools.
 *
 * Honesty (CLAUDE.md section 1): each source is stated with its facts, or as EMPTY (read, nothing on
 * file) or COULD NOT BE READ, in [com.kevin.legion.greeting.GreetingBlock]'s own words. An unread
 * source is never a clear one. Pure: no clock, no network, no Android.
 */
internal object AgendaBlock {
    /** What the pack's `due_soon` covers, said so a missing item is never read as "nothing due". */
    private const val DUE_LABEL = "To-dos and Canvas work due in the 24 hours after it was read"

    /** The pack carries no weather; the block says so rather than leave it out silently. */
    const val WEATHER_LINE = "- Weather: NOT IN THIS BLOCK (the phone's copy does not carry it). For weather, " +
        "call day get_sitrep with modules weather."

    private const val TOOL = "day read_calendar"

    /** Said in a write's tool result, for the rest of the turn that made it (history keeps text only). */
    const val TOOL_NOTE = "The agenda block in this conversation's context is now out of date. For anything " +
        "due or on the calendar, call $TOOL again; do not answer from that block."

    /** The block from [cached], as of its fetch; times on [zone]'s clock. */
    fun render(cached: CachedPack, zone: ZoneId): String {
        val pack = cached.pack
        val at = hhmm(Instant.ofEpochMilli(cached.fetchedAtMs).atZone(zone).toLocalTime())
        val by = pack.sources.associateBy { it.name }
        val lines = mutableListOf<String>()
        lines += "TODAY'S AGENDA, read from the household engine at $at (today is ${pack.today}). Answer " +
            "what he has on today, or what is due, from this block without a tool call. It holds ONLY " +
            "what is listed: call $TOOL for any other day or range, for anything not listed, for events " +
            "that already ended, and for overdue work, which this block does not carry. Every change " +
            "(complete, add, move, delete) goes through its tool, never through this block. When you " +
            "answer from it, you may say it is as of $at."
        lines += source("Today's remaining calendar events", by["today_events"])
        lines += source(DUE_LABEL, by["due_soon"]) +
            "\n    note: work already past its due time (overdue) and work due later than that is NOT listed here."
        lines += WEATHER_LINE
        return lines.joinToString("\n")
    }

    /** No pack from today in hand: the block says so, and nothing is implied about his day. */
    fun unavailable(): String =
        "TODAY'S AGENDA: not in this conversation's context (the phone has no copy read in the last 30 " +
            "minutes). This says nothing about his day: for anything on the calendar or due, call $TOOL."

    /**
     * A change was made in this conversation at [at] ([tool]): the copy read earlier may no longer be
     * true, so it is withdrawn rather than refreshed (a refresh inside 30 s hands back the same pack,
     * and the engine fetch takes ~1.6 to 2.0 s, longer than the gap to his next turn).
     */
    fun withdrawn(at: LocalTime): String =
        "TODAY'S AGENDA: withdrawn. Something was changed in this conversation at ${hhmm(at)}, so the copy " +
            "read before it is out of date. For anything on the calendar or due, call $TOOL."

    private fun source(label: String, s: GreetingSource?): String = when {
        s == null ->
            "- $label (COULD NOT BE READ, which is not the same as empty): the engine's reply did not include it."
        s.state == SourceState.FACTS ->
            (listOf("- $label (facts):") + s.items.map { "    * ${it.says}" }).joinToString("\n")
        s.state == SourceState.EMPTY -> "- $label (EMPTY: read, and nothing is on file): ${s.says}"
        else -> "- $label (COULD NOT BE READ, which is not the same as empty): ${s.reason ?: s.says}"
    }

    private fun hhmm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)
}
