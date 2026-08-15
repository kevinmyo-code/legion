package com.kevin.legion.ui.notes

/**
 * The appointment-versus-reminder call (ticket 14,
 * `.scratch/google-account-integration/issues/14-calendar-write.md`, carrying forward ticket 04's
 * answer: "an appointment and a reminder stop being the same thing... Google owns appointments.
 * LEGION owns reminders. Nothing is ever written to both stores"). "Dentist Tuesday at 3" belongs
 * on Google Calendar via [com.kevin.legion.calendar.CalendarProvider.insertEvent]; "remind me to
 * change the oil Tuesday" stays a local `ListItem`. Getting this wrong puts the item in a store the
 * driver does not expect, so the decision is made in exactly ONE place - here - rather than being
 * inferred ad hoc at the `manage_item` call site in `service/LiveToolbox.kt`.
 *
 * Pure, no Android types, so the routing branch and the wording it produces are both plain JUnit
 * tests ([ScheduleIntentResolverTest]) - the same "pure resolver, thin dispatch wrapper" split every
 * other `*Resolver` in this codebase already follows ([LedgerRecategorizeResolver],
 * [CalendarAgendaResolver]).
 */
object ScheduleIntentResolver {

    /** What a newly-scheduled item is, and therefore which store it belongs in. */
    sealed class Kind {
        /** Goes to `CalendarContract` via [com.kevin.legion.calendar.CalendarProvider.insertEvent].
         * Nothing about it is stored locally afterward - ticket 04 point 5. */
        object Appointment : Kind()

        /** Goes to the local `ListItem` table, same as every item before this ticket. */
        object Reminder : Kind()
    }

    /**
     * Decides [Kind] from the model's own signal on the `manage_item` tool call, never from
     * guessing at the item text itself - the model already has the driver's actual words and
     * intent; re-deriving that from a keyword match here would be a second, worse copy of the same
     * judgement. [explicitKind] is the tool's raw `kind` argument, expected to be `"appointment"` or
     * `"reminder"` (any case, untrimmed) when the model states one at all.
     *
     * **Defaults to [Kind.Reminder]** for anything else - null, blank, unrecognized, or genuinely
     * absent because the model did not have a clear read on it. Ticket 04's own words: "the default
     * when genuinely ambiguous is reminder, because a reminder is local, private, and trivially
     * undone." An appointment mistakenly filed as a reminder costs a re-ask; a reminder mistakenly
     * filed as a Google appointment is on every device the driver owns and is not something this
     * app can take back.
     */
    fun resolve(explicitKind: String?): Kind =
        if (explicitKind?.trim()?.equals("appointment", ignoreCase = true) == true) {
            Kind.Appointment
        } else {
            Kind.Reminder
        }

    /**
     * The confirmation sentence Alfred says, derived from [kind] rather than hardcoded at the
     * `manage_item` call site - ticket 14: "Alfred always says which he did." [whenPhrase] is
     * whatever human-readable date/time text the caller has already built (e.g. via
     * `util/Dates.kt`'s `documentDate`); null when the item carries no date at all, which can only
     * happen for [Kind.Reminder] - [Kind.Appointment] always has a date, because
     * `service/LiveToolbox.kt`'s dispatch refuses to create a calendar event without one before this
     * function is ever reached.
     */
    fun confirmationPhrase(kind: Kind, itemText: String, whenPhrase: String?): String = when (kind) {
        is Kind.Appointment -> {
            checkNotNull(whenPhrase) { "an appointment always carries a date - see this function's doc comment" }
            "Put \"$itemText\" on your calendar for $whenPhrase."
        }
        is Kind.Reminder -> if (whenPhrase != null) {
            "I'll remind you about \"$itemText\" $whenPhrase."
        } else {
            "Added \"$itemText\" to your list."
        }
    }
}
