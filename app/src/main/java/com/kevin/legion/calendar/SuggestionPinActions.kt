package com.kevin.legion.calendar

import android.content.Context
import com.kevin.legion.backend.EventsPins
import com.kevin.legion.backend.PinWrite
import com.kevin.legion.data.local.Event

/**
 * The hands path for suggestion pins (Kevin, 2026-10-09; ADR 0035: the UI toggle is the non-voice
 * path, and there is deliberately no voice tool - the Live setup payload has no room for one, so
 * `read_calendar`'s result only REPORTS who wants to go, as `wants_to_go`).
 *
 * Kept apart from [EventSuggestions], which is at the object-size ceiling, and calls the same
 * writer ([EventsPins]) any other path would.
 */
object SuggestionPinActions {

    /** [rows] as the day view shows them: all of them, or with "Pinned only" on, only those at
     * least one household member has pinned. A pure function so the filter is testable. */
    fun visibleRows(rows: List<Event>, pinnedOnly: Boolean): List<Event> =
        if (pinnedOnly) rows.filter { SuggestionPin.parse(it.pinnedByJson).isNotEmpty() } else rows

    /** The pin button: pins ([pinned] true) or unpins the phone's user on [row], and says in words
     * what happened, "queued" included. Re-reads the row first, like [EventSuggestions.addToPlans]. */
    suspend fun setPinned(context: Context, row: Event, pinned: Boolean): EventSuggestions.Outcome {
        val fresh = EventSuggestions.live(context, row)
            ?: return EventSuggestions.Outcome.Refused(
                "Nothing was changed: this suggestion is no longer on the calendar.",
            )
        return when (val write = EventsPins.setPinned(context, fresh, pinned)) {
            is PinWrite.Refused -> EventSuggestions.Outcome.Refused(write.sentence)
            is PinWrite.Applied -> EventSuggestions.Outcome.Done(appliedSentence(pinned, write.queued), write.queued)
        }
    }

    private fun appliedSentence(pinned: Boolean, queued: Boolean): String = when {
        queued && pinned -> "Pinned on this phone. Queued - not on the engine yet."
        queued -> "Unpinned on this phone. Queued - not on the engine yet."
        pinned -> "Pinned: the household can see you want to go."
        else -> "Unpinned."
    }
}
