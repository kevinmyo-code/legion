package com.kevin.legion.service.tools.day

import android.content.Context
import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.data.local.activeByKindInLocalWindow
import com.kevin.legion.notes.NotesController
import com.kevin.legion.notes.TextMatches
import com.kevin.legion.notes.tieredTextMatches
import com.kevin.legion.service.LiveCardReads
import com.kevin.legion.service.tools.day.EventRemovalWords.fail
import com.kevin.legion.service.tools.day.EventRemovalWords.whenPhrase
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.json.JSONObject

/**
 * `manage_item schedule` for a calendar entry: moves it to a new time and/or day (2026-11-22: "put
 * an entry tomorrow at seven", then "actually, seven thirty", and the assistant tried a remove, then
 * an add with no item). Not destructive, so no question: it moves and says where to.
 *
 * Matched like a remove ([EventRemoval]): title tiers against reminders (a stronger reminder keeps
 * its own schedule path), today and the next days; several matches prefer the day the conversation
 * is about ([RelativeDay.TurnDates.aboutDay]), else it asks which one. Only the parts given change:
 * a time alone keeps the day, a day alone keeps the clock time; the length is kept. Written through
 * [NotesController.updateAppointment] (local row, push, outbox), the Calendar edit's own funnel.
 *
 * **A follow-up correction moves, never doubles.** An add right after an entry was added or moved
 * in this conversation (this turn or his previous one) with no item, or in his next turn with that
 * entry's own title on its own day, moves that entry instead of adding a second ("also", "another" or "too" in his
 * words keep it an add). An add with no item and no such entry asks "add what?" ([LiveToolbox]).
 */
internal object EventMove {
    private data class Recent(val eventId: Long, val turn: Long)

    @Volatile private var recent: Recent? = null

    /** The conversation ended: no entry is "the one just discussed" any more. */
    fun reset() {
        recent = null
    }

    /** An entry was just added or moved in this turn: a follow-up add may mean it. */
    fun noteTouched(eventId: Long) {
        recent = Recent(eventId, EventRemoval.currentTurn)
    }

    /**
     * `schedule` for [phrase] to [date] and/or [time]. Null when no calendar entry is the better
     * match, or nothing moves: the reminder path runs as before.
     */
    suspend fun move(
        context: Context,
        phrase: String,
        date: LocalDate?,
        time: LocalTime?,
        reminders: List<ListItem>,
    ): JSONObject? {
        if (date == null && time == null) return null
        val zone = RelativeDay.clock().zone
        val today = RelativeDay.today()
        val fromMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val lastDay = today.plusDays(EventRemovalWords.LOOK_AHEAD_DAYS + 1)
        val toMs = lastDay.atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val dao = CarDatabase.getDatabase(context).eventDao()
        val events = tieredTextMatches(phrase, dao.activeByKindInLocalWindow(EventKind.EVENT, fromMs, toMs, zone)) {
            it.title
        }
        val reminderTier = tieredTextMatches(phrase, reminders) { it.text }.tier
        if (events.tier == TextMatches.NONE || reminderTier < events.tier) return null
        val about = kotlin.coroutines.coroutineContext[RelativeDay.TurnDates]?.aboutDay
        val onAbout = events.items.filter { EventRemovalWords.rowDay(it, zone) == about }
        val chosen = events.items.singleOrNull() ?: onAbout.singleOrNull()
            ?: return whichOne(events.items, today, zone)
        return moveRow(context, chosen, date, time)
    }

    /**
     * An add that follows an entry just added or moved: with no item, or that entry's own title on
     * its own day, it is a correction and moves the entry. Null when it is a real add.
     */
    @Suppress("ReturnCount") // no recent entry, a real add, a gone row: each falls to the add
    suspend fun followUp(
        context: Context,
        item: String,
        date: LocalDate?,
        time: LocalTime?,
        userText: String?,
    ): JSONObject? {
        val last = recent?.takeIf { it.turn >= EventRemoval.currentTurn - 1 } ?: return null
        if (date == null && time == null) return null
        if (EventRemovalWords.saysAnother(userText)) return null
        val row = CarDatabase.getDatabase(context).eventDao().getById(last.eventId)
            ?.takeIf { !it.deleted && it.kind == EventKind.EVENT } ?: return null
        // Its own title counts only from his next turn: one turn may add two of a kind on purpose.
        val onItsDay = date == null || date == EventRemovalWords.rowDay(row, RelativeDay.clock().zone)
        val sameTitle = item.trim().equals(row.title.trim(), ignoreCase = true)
        val sameEntry = item.isBlank() || last.turn < EventRemoval.currentTurn && sameTitle && onItsDay
        return if (sameEntry) moveRow(context, row, date, time) else null
    }

    private suspend fun moveRow(context: Context, row: Event, date: LocalDate?, time: LocalTime?): JSONObject {
        val zone = RelativeDay.clock().zone
        val (start, end, allDay) = newTimes(row, date, time, zone)
        if (!NotesController.updateAppointment(context, row.id, row.title, start, end, allDay)) {
            return fail("Nothing was moved: \"${row.title}\" could not be changed. It is where it was.")
        }
        noteTouched(row.id)
        val moved = row.copy(startsAt = start, endsAt = end, allDay = allDay)
        val day = EventRemovalWords.rowDay(moved, zone) ?: RelativeDay.today()
        // The day it landed on takes the screen unless a card already shows it; an open card of the
        // old day refreshes itself (LiveCards).
        LiveCardReads.showDayAfterWrite(
            context,
            day.atStartOfDay(zone).toInstant().toEpochMilli(),
            day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
        )
        return JSONObject().put("success", true).put("title", row.title).put("date", day.toString())
            .put("message", "Moved \"${row.title}\" to ${whenPhrase(moved, RelativeDay.today(), zone)}.")
    }

    /** The new start, end and all-day flag: only what was given changes, and the length is kept. */
    private fun newTimes(row: Event, date: LocalDate?, time: LocalTime?, zone: ZoneId): Triple<Long, Long, Boolean> {
        val oldDay = EventRemovalWords.rowDay(row, zone) ?: RelativeDay.today()
        val day = date ?: oldDay
        if (row.allDay && time == null) {
            val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            return Triple(start, start + DAY_MS, true)
        }
        val kept = (row.endsAt ?: 0L) - (row.startsAt ?: 0L)
        val length = if (row.allDay || kept <= 0) HOUR_MS else kept
        val clock = time ?: java.time.Instant.ofEpochMilli(row.startsAt ?: 0L).atZone(zone).toLocalTime()
        val start = day.atTime(clock).atZone(zone).toInstant().toEpochMilli()
        return Triple(start, start + length, false)
    }

    private fun whichOne(events: List<Event>, today: LocalDate, zone: ZoneId): JSONObject {
        val named = events.sortedBy { it.startsAt ?: 0L }.map { "\"${it.title}\", ${whenPhrase(it, today, zone)}" }
        val more = named.size - EventRemoval.MAX_NAMED
        val tail = if (more > 0) "; and $more more: a day narrows it" else ""
        val listed = named.take(EventRemoval.MAX_NAMED).joinToString("; ")
        return fail("Nothing was moved. Which one? $listed$tail.", asks = true)
    }

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24 * HOUR_MS
}
