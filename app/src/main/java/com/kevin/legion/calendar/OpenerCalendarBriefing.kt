package com.kevin.legion.calendar

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
 *
 * **2026-08-23 (aspect-engine ticket 19 point 5): the source switched from Google directly to the
 * central date store** (`.scratch/aspect-engine/issues/05-central-date-database.md` answer point
 * 4: "agenda is a query... one fact, one place") - the caller
 * (`service/AriaForegroundService.kt`'s `buildOpenerSituation`) now reads
 * [com.kevin.legion.engine.dates.DatesAgenda.windowed] instead of
 * [CalendarProvider.eventsInWindow] directly, and maps the result into [BriefingEvent] before
 * calling [forOpener]. This class's own logic is unchanged and still three outcomes, never a
 * silence the model can fill - it just no longer knows or cares whether an event came from Google
 * or was created inside LEGION.
 *
 * **`hasPermission` still reflects Google's OWN read permission, not "is the central store
 * readable"** (the store is Room; it is always readable). This is a deliberate, conservative
 * reading of a fork ticket 19 does not fully resolve: a `legion`-sourced event is always a real
 * fact the app can state safely, but when Google is unreadable the merged agenda can never be
 * called EXHAUSTIVE - there may be real appointments this device simply cannot see - and
 * [NO_PERMISSION]'s whole point is refusing to claim more certainty than the app actually has.
 * The cost, accepted rather than hidden: a legion-created event can go unmentioned in the opener
 * on a device with calendar permission refused, even though the app fully knows about it. Low
 * stakes for a one-line greeting; flagged here as a reasoned call, not a locked answer.
 */
object OpenerCalendarBriefing {

    /** How far ahead the opener looks. Long enough to catch "later today", short enough that the
     * model is not tempted to narrate a week. */
    const val WINDOW_HOURS = 12L

    /** At most this many events go into the prompt - a packed day is a greeting, not an agenda. */
    const val MAX_EVENTS = 4

    /**
     * One agenda item worth mentioning in the opener - deliberately NOT
     * [CalendarProvider.GoogleCalendarEvent], since the source is now the merged central date
     * store and an event living entirely inside LEGION was never a Google row to begin with.
     * [allDay] is not a concept the Dates aspect schema tracks (aspect-engine ticket 19's field
     * list has no such column - see [com.kevin.legion.engine.dates.DatesAspectSeeder]'s own doc
     * comment), so it always defaults false here; an imported all-day Google event still renders
     * with its literal (UTC-midnight) time rather than an "(all day)" label. Known v1 limitation,
     * not a silent behavior change from before this switch - the old Google-direct path did carry
     * a real `allDay` bit and this one does not yet.
     */
    data class BriefingEvent(
        val title: String,
        val startMs: Long,
        val endMs: Long,
        val allDay: Boolean = false,
        /** Mirrors [com.kevin.legion.engine.dates.DatesAgenda.AgendaItem.dueIsInferred] - ticket
         * 01 ruling 2's "rendered in words" clause applies to spoken content exactly as much as it
         * does to a screen: an inferred "tomorrow" spoken as a bare time would read as a stated
         * appointment, the same lie a bare on-screen date would be. */
        val dueIsInferred: Boolean = false,
    )

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
     * [com.kevin.legion.engine.dates.DatesAgenda.windowed] returned for the next [WINDOW_HOURS],
     * mapped to [BriefingEvent]; pass [hasPermission] `false` to get [NO_PERMISSION] instead - see
     * this class's own doc comment for exactly what [hasPermission] means now that the source is
     * the merged central store rather than Google directly.
     */
    fun forOpener(
        events: List<BriefingEvent>,
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
            when {
                // Ticket 01 ruling 2: an inferred date is spoken as what it is, in words, never as
                // a bare time that would read as something the user actually scheduled.
                event.dueIsInferred -> "\"$title\" (showing tomorrow, no date set)"
                event.allDay -> "\"$title\" (all day)"
                else -> "\"$title\" at ${Instant.ofEpochMilli(event.startMs).atZone(zone).format(TIME_FMT)}"
            }
        }
        return "Their calendar, read just now, has exactly these and nothing else for the next " +
            "$WINDOW_HOURS hours: $listed. You may mention the next one briefly if it is soon " +
            "enough to matter. Never name an appointment, a person or a plan that is not on that " +
            "list - if it is not there, it does not exist. "
    }
}
