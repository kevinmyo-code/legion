package com.kevin.legion.ui.notes

/**
 * Pure logic behind editing/deleting a Google Calendar row from the Log stream (ticket 22,
 * `.scratch/google-account-integration/issues/22-edit-calendar-entries-from-log.md`). Same "pure
 * resolver, thin composable wrapper" split every other `*Resolver` in this codebase already follows
 * ([ScheduleIntentResolver], [CalendarAgendaResolver], [com.kevin.legion.ui.ledger.LedgerRecategorizeResolver])
 * - no Compose, no Android types, no `Context`, so every branch here is a plain JUnit test
 * ([CalendarEditResolverTest]). The actual writes are Android-bound and live in
 * [com.kevin.legion.calendar.CalendarProvider]; `ui/notes/InboxScreen.kt` is the thin wrapper that
 * calls both.
 *
 * **This reverses ticket 13's read-only rule for a Google row, deliberately, on Kevin's call
 * 2026-08-13.** It does not reopen ticket 04's "Google owns appointments, LEGION owns reminders,
 * nothing is ever written to both": editing or deleting a Google event happens IN PLACE, on
 * Google's own copy, through [com.kevin.legion.calendar.CalendarProvider] - exactly the same
 * provider boundary [com.kevin.legion.calendar.CalendarProvider.insertEvent] already writes
 * through for creation. Nothing here ever copies a Google event into Room, and nothing here ever
 * routes a Google row through [com.kevin.legion.notes.NotesController] - that controller's edit/
 * remove path stays exactly what it was, untouched, for local rows only (ticket 22 point 7).
 */
object CalendarEditResolver {

    /**
     * `CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR`, mirrored here as a plain `Int` so this
     * file stays Android-free (it cannot import `CalendarContract` and still be a plain JUnit
     * target). `calendar/CalendarProvider.kt`'s own `writableGoogleCalendars` filters on the
     * identical value - the two must never drift apart from each other.
     */
    const val CAL_ACCESS_CONTRIBUTOR = 500

    /**
     * What a Log-stream row for a Google Calendar event may offer - ticket 22 point 4 (a read-only
     * calendar's events must never be editable, and must say why) and point 2 (a recurring event
     * needs a "this one or all of them" prompt before either an edit or a delete acts).
     */
    enum class RowAction {
        /** The owning calendar's access level is below [CAL_ACCESS_CONTRIBUTOR] - ticket 17's
         * "Holidays in United States" case (access level 200). No edit, no delete affordance;
         * [READ_ONLY_REASON] is shown on the row instead of offering a write that would fail. */
        READ_ONLY,

        /** Writable and not recurring - nothing to disambiguate. Edit/delete act on the event
         * directly, with no "this one or all of them" prompt (ticket 22 point 2's own "a
         * non-recurring event gets no prompt - there is nothing to disambiguate"). */
        EDITABLE,

        /** Writable and recurring - edit/delete must first ask [Scope] via [scopePrompt]. */
        EDITABLE_RECURRING,
    }

    /** Shown on a [RowAction.READ_ONLY] row in place of an edit/delete affordance - ticket 22
     * point 4's "say why", never a silently-offered write that would fail on the provider. */
    const val READ_ONLY_REASON = "This calendar is read-only."

    /** Decides [RowAction] from the two facts a row carries about its Google event - the owning
     * calendar's `CALENDAR_ACCESS_LEVEL` and whether the event recurs
     * ([com.kevin.legion.calendar.CalendarProvider.GoogleCalendarEvent.recurring]). Read access
     * ([CAL_ACCESS_READ] = 200, below [CAL_ACCESS_CONTRIBUTOR]) always resolves to [RowAction.READ_ONLY]
     * regardless of [recurring] - a calendar Kevin cannot write to at all has nothing to disambiguate. */
    fun rowAction(calendarAccessLevel: Int, recurring: Boolean): RowAction = when {
        calendarAccessLevel < CAL_ACCESS_CONTRIBUTOR -> RowAction.READ_ONLY
        recurring -> RowAction.EDITABLE_RECURRING
        else -> RowAction.EDITABLE
    }

    /** Which occurrences an edit or delete should touch - the answer to the "this one or all of
     * them" prompt (ticket 22 point 2). Mechanics, from `research/02-calendar-api-choice.md` §5:
     * [THIS_ONE] becomes an exception row via `Events.CONTENT_EXCEPTION_URI` with
     * `ORIGINAL_INSTANCE_TIME` set to the occurrence's own `BEGIN`
     * ([com.kevin.legion.calendar.CalendarProvider.updateEventOccurrence]/`deleteEventOccurrence`);
     * [ALL] updates or deletes the parent `Events` row directly
     * ([com.kevin.legion.calendar.CalendarProvider.updateEventSeries]/`deleteEventSeries`). */
    enum class Scope { THIS_ONE, ALL }

    /** Which write a row button triggered - [scopePrompt] and the delete confirm both branch on
     * this, because a delete always needs a confirm (ticket 22 point 3) and an edit never does
     * unless it is also recurring. */
    enum class Operation { EDIT, DELETE }

    /**
     * The "this one or all of them" prompt's own words - `null` when there is nothing to
     * disambiguate: [RowAction.READ_ONLY] (nothing is offered at all) or a plain
     * [RowAction.EDITABLE] [Operation.EDIT] (ticket 22 point 2's explicit "a non-recurring event
     * gets no prompt"). A plain [RowAction.EDITABLE] [Operation.DELETE] still needs a confirm - see
     * [SINGLE_DELETE_CONFIRM] - it is just never a SCOPE choice, because there is only one event to
     * choose between.
     *
     * Kept as one function (not "editPrompt"/"deletePrompt") so the "no prompt on a non-recurring
     * edit" and "always a scope choice on a recurring edit-or-delete" rules stay in the one place
     * that decides both, rather than two functions that could drift out of step with each other.
     */
    fun scopePrompt(action: RowAction, operation: Operation): String? = when {
        action != RowAction.EDITABLE_RECURRING -> null
        operation == Operation.EDIT -> "Just this one, or the whole series?"
        else -> "Delete just this one, or the whole series?"
    }

    /** The plain (non-recurring) delete confirm's own words - ticket 22 point 3: delete is "behind
     * a confirm", never silent, because it propagates to Google within seconds (proved on-device
     * 2026-08-13, ticket 14) and is not undoable from here. A recurring delete is confirmed by
     * [scopePrompt] instead - picking [Scope.THIS_ONE] or [Scope.ALL] there IS the confirmation,
     * so this string is never shown alongside it. */
    const val SINGLE_DELETE_CONFIRM = "Delete this event? This can't be undone from here."
}
