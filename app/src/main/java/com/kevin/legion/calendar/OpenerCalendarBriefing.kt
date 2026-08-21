package com.kevin.legion.calendar

import com.kevin.legion.calendar.CalendarProvider.GoogleCalendarEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns the user's real, just-queried calendar into the sentence the startup opener hands the
 * model - pure logic, no Android types, so it is unit-testable (the same "pure builder, thin
 * dispatch" split [CalendarReadToolLogic] already sets).
 *
 * **Why this exists (2026-08-21, Kevin, on-device): the opener invented a lunch appointment with a
 * "Sam" who does not exist in his life.** The opener prompt asked the model to work in anything
 * "notable coming up" and then handed it time, place, weather and car - and no schedule whatsoever.
 * An instruction to mention what is coming up, with nothing supplied to mention, is not neutral: an
 * absent fact reads as free to invent, which is the exact failure `ai/AriaBrain.kt`'s fact clause
 * already names (the invented "dentist appointment at 3"). The system prompt forbade invention
 * globally while the turn-level prompt asked for it, and the nearer instruction won.
 *
 * So the opener now states the calendar as fact or states its absence as fact. Three outcomes,
 * never a silence the model can fill:
 *
 *  - no permission -> [NO_PERMISSION], which forbids the whole subject. Deliberately NOT "nothing
 *    on today": an unreadable calendar rendered as an empty one is the same lie
 *    [CalendarReadToolLogic.PERMISSION_MISSING_MESSAGE] refuses to tell.
 *  - readable and empty -> [NOTHING_SCHEDULED]. An empty day is a real answer.
 *  - readable with events -> the events themselves, closed with "if it is not on this list it does
 *    not exist" so the list reads as exhaustive rather than as an example.
 */
object OpenerCalendarBriefing {

    /** How far ahead the opener looks. Long enough to catch "later today", short enough that the
     * model is not tempted to narrate a week. */
    const val WINDOW_HOURS = 12L

    /** At most this many events go into the prompt - a packed day is a greeting, not an agenda. */
    const val MAX_EVENTS = 4

    const val NO_PERMISSION =
        "You cannot see the user's calendar at all right now (calendar permission is not " +
            "granted). Say NOTHING about their schedule, appointments, meetings or plans - not " +
            "that they have something, and not that they are clear. You do not know. "

    const val NOTHING_SCHEDULED =
        "Their calendar HAS been checked just now and there is nothing at all on it for the next " +
            "$WINDOW_HOURS hours. Do not mention any appointment, meeting or plan - there are " +
            "none, and naming one would be inventing it. "

    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /**
     * The calendar sentence for the opener. [events] is whatever
     * [CalendarProvider.eventsInWindow] returned for the next [WINDOW_HOURS]; pass [hasPermission]
     * `false` to get [NO_PERMISSION] instead, since that query returns an empty list for a refused
     * permission AND an empty list for a clear day, and those two must never collapse into the
     * same spoken line.
     */
    fun forOpener(
        events: List<GoogleCalendarEvent>,
        nowMs: Long,
        zone: ZoneId,
        hasPermission: Boolean,
    ): String {
        if (!hasPermission) return NO_PERMISSION
        val upcoming = events
            .filter { it.allDay || it.endMs > nowMs }
            .sortedBy { it.startMs }
            .take(MAX_EVENTS)
        if (upcoming.isEmpty()) return NOTHING_SCHEDULED

        val listed = upcoming.joinToString("; ") { event ->
            val title = event.title.trim().ifEmpty { "(untitled)" }
            if (event.allDay) "\"$title\" (all day)"
            else "\"$title\" at ${Instant.ofEpochMilli(event.startMs).atZone(zone).format(TIME_FMT)}"
        }
        return "Their calendar, read just now, has exactly these and nothing else for the next " +
            "$WINDOW_HOURS hours: $listed. You may mention the next one briefly if it is soon " +
            "enough to matter. Never name an appointment, a person or a plan that is not on that " +
            "list - if it is not there, it does not exist. "
    }
}
