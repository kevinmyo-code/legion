package com.kevin.legion.service.cascade

import com.kevin.legion.calendar.DueList
import com.kevin.legion.calendar.DueSummary
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Today's agenda in a spoken conversation's context (voice quick wins item C, ADR 0093).
 *
 * **Built from the phone's own read of today, the same one `day read_calendar` makes**
 * ([com.kevin.legion.calendar.CalendarWindowRead], 2026-11-16): today's calendar events, open work
 * due today and open overdue work, as of the read. It replaced the greeting pack's `due_soon`, which
 * carried no overdue work: the stage 5 gate saw the model answer "what do I have today" from the
 * pack and drop the overdue item every time, so the block was narrowed to calendar-only questions.
 * With overdue work in it, today, due and overdue questions may be answered from it again.
 *
 * **A brief-authorised exception to CLAUDE.md section 7's "pull-based tools always"**: the block
 * says when it was read and sends everything else (other days, the full list past a cap, weather,
 * every write) back to the tools.
 *
 * Honesty (CLAUDE.md section 1): each part is stated with its facts, as EMPTY (read, nothing on
 * file) or as COULD NOT BE READ, and due work uses [DueList]'s own sentences, so the block says it
 * the way the tool does. A list past its cap ends with "and N more", never a silent cut. Pure: no
 * clock, no network, no Android.
 */
internal object AgendaBlock {
    /** Older than this, the read is not used: the block is withdrawn for the next turn. */
    const val MAX_AGE_MS = 30 * 60_000L

    /** At most this many events listed; the rest are counted. */
    const val MAX_EVENTS = 12

    /** At most this many overdue items, and separately this many due today, are listed. */
    const val MAX_DUE_LISTED = 8

    /** Read at open, nothing was read: the block says so. */
    const val NOT_READ_IN_TIME = "the phone's read of today did not finish in time"
    const val READ_FAILED = "the phone's calendar could not be read just now"

    /** The pack carries no weather; the block says so rather than leave it out silently. */
    const val WEATHER_LINE = "- Weather: NOT IN THIS BLOCK. For weather, call day get_sitrep with modules weather."

    private const val TOOL = "day read_calendar"
    private const val DUE_LABEL = "Open work due today, calendar tasks and dated to-dos"
    private const val OVERDUE_LABEL = "Overdue open work (past due, not done)"

    /** Said in a write's tool result, for the rest of the turn that made it (history keeps text only). */
    const val TOOL_NOTE = "The agenda block in this conversation's context is now out of date. For anything " +
        "due or on the calendar, call $TOOL again; do not answer from that block."

    /** One calendar event as the block lists it (kind = event only; tasks are under due work). */
    data class AgendaEvent(
        val title: String,
        val startMs: Long,
        val endMs: Long,
        val allDay: Boolean,
        val location: String? = null,
    )

    /** What the phone read for today ([com.kevin.legion.calendar.CalendarWindowRead.today]). */
    data class Snapshot(val events: List<AgendaEvent>, val due: DueSummary, val readAtMs: Long)

    /** The block from [s], as of its read; times on [zone]'s clock. */
    fun render(s: Snapshot, zone: ZoneId): String {
        val readAt = Instant.ofEpochMilli(s.readAtMs).atZone(zone)
        val at = hhmm(readAt.toLocalTime())
        val day = readAt.toLocalDate()
        val dayName = day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
        val lines = mutableListOf<String>()
        lines += "TODAY'S AGENDA, read from the phone's own calendar and to-do list at $at (today is " +
            "$dayName $day). It is what $TOOL returns for today, read at $at: today's events, open work " +
            "due today, and open overdue work. A question about today (what he has today, what is due " +
            "today, what is overdue, what is on his calendar today) may be answered from this block " +
            "without a tool call; you may say it is as of $at. A plain \"what's due\" with no day named " +
            "means due today plus overdue: answer it from this block. When you say what he has today or " +
            "what is due, include the overdue work, and where a line says more are not listed, say how many. " +
            "Call $TOOL for any other day or range (tomorrow, this week), for work due after today, for " +
            "the full list behind an \"and N more\" line, and for anything not in this block. Every change " +
            "(complete, add, move, delete) goes through its tool, never through this block."
        lines += events(s.events, s.readAtMs, zone)
        lines += duePart(s.due, overdue = false)
        lines += duePart(s.due, overdue = true)
        if (s.due.doneCount > 0) lines += "- Already done today: ${s.due.doneCount}."
        lines += WEATHER_LINE
        return lines.joinToString("\n")
    }

    /** No read of today in hand: the block says so, and nothing is implied about his day. */
    fun unavailable(why: String): String =
        "TODAY'S AGENDA: not in this conversation's context ($why). This says nothing about his day: " +
            "for anything on the calendar or due, call $TOOL."

    /**
     * A change was made in this conversation at [at]: the copy read earlier may no longer be true,
     * so it is withdrawn rather than re-read.
     */
    fun withdrawn(at: LocalTime): String =
        "TODAY'S AGENDA: withdrawn. Something was changed in this conversation at ${hhmm(at)}, so the copy " +
            "read before it is out of date. For anything on the calendar or due, call $TOOL."

    /** The read is older than [MAX_AGE_MS], or from another day: not used. */
    fun expired(readAt: LocalTime): String =
        "TODAY'S AGENDA: withdrawn. The copy read at ${hhmm(readAt)} is more than 30 minutes old or from " +
            "another day. For anything on the calendar or due, call $TOOL."

    /**
     * The block as his next turn sees it: "" when none was read (a test, an opener or a check-in),
     * [withdrawn] after a write, [expired] when [read] is older than [MAX_AGE_MS] or from another
     * day than [nowMs], else [read] as it was.
     */
    fun current(read: String, readAtMs: Long, changedAt: LocalTime?, nowMs: Long, zone: ZoneId): String {
        val then = Instant.ofEpochMilli(readAtMs).atZone(zone)
        val otherDay = then.toLocalDate() != Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return when {
            read.isEmpty() -> ""
            changedAt != null -> withdrawn(changedAt)
            readAtMs <= 0L -> read // an unavailable() line: nothing to age out
            nowMs - readAtMs > MAX_AGE_MS || otherDay -> expired(then.toLocalTime())
            else -> read
        }
    }

    private fun events(events: List<AgendaEvent>, readAtMs: Long, zone: ZoneId): String {
        val label = "Today's calendar events"
        if (events.isEmpty()) return "- $label (EMPTY: read, and nothing is on the calendar today)."
        val shown = events.take(MAX_EVENTS).map { "    * ${eventLine(it, readAtMs, zone)}" }
        val more = events.size - MAX_EVENTS
        val tail = if (more > 0) listOf("    * ${DueList.moreLine(more)}") else emptyList()
        return (listOf("- $label (facts): ${events.size} on the calendar.") + shown + tail).joinToString("\n")
    }

    private fun eventLine(e: AgendaEvent, readAtMs: Long, zone: ZoneId): String {
        val time = if (e.allDay) {
            "all day"
        } else {
            val from = hhmm(Instant.ofEpochMilli(e.startMs).atZone(zone).toLocalTime())
            val to = hhmm(Instant.ofEpochMilli(e.endMs).atZone(zone).toLocalTime())
            if (e.endMs > e.startMs) "$from to $to" else from
        }
        val place = e.location?.takeIf { it.isNotBlank() }?.let { ", at $it" }.orEmpty()
        val over = if (!e.allDay && e.endMs in 1 until readAtMs) " (already over when read)" else ""
        return "${e.title}, $time$place$over"
    }

    /**
     * Open work due later today, or ([overdue]) open overdue work, in [DueList]'s own sentence. Both
     * due sources unread ([com.kevin.legion.calendar.DueReader] names calendar tasks and to-dos) is
     * COULD NOT BE READ; one unread is PARTLY READ; never EMPTY unless both were read.
     */
    private fun duePart(s: DueSummary, overdue: Boolean): String {
        val label = if (overdue) OVERDUE_LABEL else DUE_LABEL
        val entries = s.open.filter { it.overdue == overdue }
        val what = if (overdue) "nothing overdue" else "nothing open"
        return when {
            entries.isNotEmpty() ->
                "- $label (facts${partly(s)}): ${DueList.openSays(entries, overdue, s.today, MAX_DUE_LISTED)}"
            s.unreadable.size >= 2 ->
                "- $label (COULD NOT BE READ, which is not the same as empty): ${DueList.unreadableSays(s)}"
            s.unreadable.isNotEmpty() ->
                "- $label (PARTLY READ, which is not the same as empty): $what was found in what could be " +
                    "read. ${DueList.unreadableSays(s)}"
            overdue -> "- $label (EMPTY: read, and nothing is overdue)."
            else -> "- $label (EMPTY: read, and nothing open is due later today)."
        }
    }

    private fun partly(s: DueSummary): String =
        if (s.unreadable.isEmpty()) "" else "; PARTLY READ: ${DueList.unreadableSays(s)}"

    private fun hhmm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)
}
