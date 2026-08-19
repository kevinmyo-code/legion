---
map: google-account-integration
ticket: 04
title: "Google owns events now - what happens to the local timed items?"
type: grilling
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Google owns events now - what happens to the local timed items?

**Unblocked 2026-08-13 by [ticket 02](02-calendar-api-choice.md): the store is `CalendarContract`,
the on-device provider.** Two consequences that change this ticket's shape before you start.
Question 5 nearly evaporates - the provider is local, so "can the agenda render offline" is yes for
free, and the `Instances` time-range URI is *the same query shape* `notes-lists-calendar` ticket 08
already chose. Question 2 gets easier too: the provider carries `RRULE`/`RDATE`/`EXRULE`/`EXDATE`
and real exception rows, so nothing about Google's recurrence has to be re-implemented, only read.
Question 3, the two-alarm-systems problem, is untouched and is now the expensive half of this ticket.

## Question

Settled decision 2 hands timed events to Google. The notes domain was built on the opposite premise
one week earlier: `notes-lists-calendar` charting decision 6 made a calendar event **the same
entity** as a list item with an optional `startsAt`, and its ticket 09 decided the whole domain is
local-only because Drive had no working sync. Both premises have now moved.

Decide, concretely, against the real code (`data/local/ListItem.kt`, `ItemList.kt`,
`ListItemSkip.kt`, `notes/Recurrence.kt`, `RepeatKind.kt`, `NextOccurrence.kt`, `AlarmScheduler.kt`,
`service/ReminderAlarmReceiver.kt`, `ui/notes/`, `ui/TodayScreen.kt`):

1. **Existing rows with a `startsAt`.** Migrated up to Google, left where they are as legacy, or
   dual-written? If migrated: a Room migration, a one-time push, or a user-initiated action?
2. **`Recurrence` / `RepeatKind` / `ListItemSkip`.** The local model is a deliberately small
   hand-rolled set - daily / weekly-on-days / monthly-on-date / yearly, occurrences computed on read
   and never materialised, skip-only editing, never move an occurrence. Does it retire entirely,
   survive for untimed repeating chores, or stay as a shadow of Google's RRULE?
3. **Alarms.** `notes-lists-calendar` ticket 03 decided local `setAndAllowWhileIdle` alarms with a
   dedicated notification channel and a MISSED section. Google Calendar fires its own notifications.
   **Two systems would now fire for the same event.** Decide which one owns a reminder, and whether
   LEGION's channel and MISSED behaviour survive.
4. **Place triggers.** Settled decision 3 keeps them local. Confirm nothing about them breaks when
   the timed half moves out.
5. **What the local table still stores about a Google event**, if anything - an id for linking,
   nothing at all, or a cache. This decides whether the agenda view can render offline, which
   ticket 10 then depends on.
6. **Whether any of this needs a Room migration**, and if so what it is. Additive only, verbatim
   generated SQL, `exportSchema`, migration test (CLAUDE.md §5/§7).

The MISSED-reminder decision was made once already on the argument that silently dropping something
is this repo's recurring sin. Do not quietly undo it here.

## Answer

**An appointment and a reminder stop being the same thing. Google owns appointments. LEGION owns
reminders. Nothing is ever written to both stores, so there is no sync, no mirror, no migration and
no schema change.**

Resolved 2026-08-13 on the orchestrator's recommendation, delegated by Kevin ("resolve them all with
your default recs"). **Not put to Kevin individually** - flagged as a reopen, because this is the
most consequential ticket on the map and it re-reads a decision he made six days ago.

### The split

`ListItem.startsAt` stops meaning *"this is a calendar event"* (`notes-lists-calendar` charting
decision 6) and starts meaning *"remind me about this task at T"*. Read the existing rows and this
is what they already were: a car task's due date, a place reminder's time. They were never
appointments. A **calendar event** - a thing with a start time that belongs on a calendar, that
somebody else might also see, that Kevin would want on a laptop - lives in `CalendarContract` and
nowhere else.

**This is what makes settled decision 2 cheap.** "Google owns a timed event" bought the removal of a
recurrence translation layer. Taken one step further it removes the sync problem too, because the two
stores never hold the same row.

### The six questions

1. **Existing rows with a `startsAt`: nothing happens to them.** No migration, no one-time push, no
   dual write. Under the new reading they are reminders and they are already in the right place.
   Pushing them to Google would manufacture calendar entries for things that were never appointments,
   and would be irreversible in the way that matters (they would then exist on every device Kevin
   owns). If it turns out at build time that some rows genuinely are appointments, he moves those by
   hand - the domain is six days old.
2. **`Recurrence` / `RepeatKind` / `ListItemSkip` survive entirely unchanged.** They were built for
   repeating *chores* - "a recurring item cannot be ticked" is chore semantics, not appointment
   semantics - and that is exactly what stays local. Google's `RRULE`/`EXDATE` is **read-only** to
   LEGION via the `Instances` URI, which expands a series for us. **No translation layer is ever
   written, in either direction.**
3. **Alarms: LEGION fires for its own reminders. Google Calendar fires for its own events. Neither
   fires for the other's.** There is no double-fire because no item exists in both stores. The
   dedicated notification channel, SNOOZE, `exact`/`exactDowngraded`, and `missedAt`/
   `missedDismissedAt` all survive untouched and all keep applying to reminders only.
   **`AlarmScheduler` is not modified by this map.**
   **The sharp edge, stated so it is not discovered later:** LEGION will therefore NOT notify Kevin
   about a Google Calendar event. Google Calendar already does, better, on every device. Reading an
   event onto the Today surface is not the same as owning its alarm, and building a second
   notification for it is precisely the double-fire this split exists to avoid.
4. **Place triggers: untouched.** They have no `startsAt`, they never went near a calendar, and
   settled decision 3 keeps them local. Nothing here reaches them.
5. **The local table stores NOTHING about a Google event.** No id, no cache, no shadow row, no
   tombstone. The agenda reads `CalendarContract.Instances` at render time. This is the single most
   load-bearing call in the ticket: it is what means there is no dedup, no drift, no last-write-wins,
   no reconciliation, and nothing to lose. Ticket 02 established the provider is local and offline,
   so a cache would buy nothing anyway. It also hands ticket 07 a free guarantee - **no calendar
   content is in the database, so none of it reaches the whole-database Drive backup.**
6. **No Room migration. No schema change at all.** That is the payoff of not mirroring.

### What this actually costs, said plainly

- **A voice-created event has to go somewhere, and Alfred must choose.** "Remind me to change the
  oil Tuesday" is a reminder; "dentist Tuesday at 3" is an appointment. Getting that wrong puts the
  item in a store Kevin does not expect. **Mitigation, and it is the same one the notes domain
  already uses: Alfred says which one he did.** "Put that on your calendar for Tuesday at 3" versus
  "I'll remind you Tuesday." Ticket 05's grammar rule, applied here. The default when genuinely
  ambiguous is **reminder**, because a reminder is local, private, and trivially undone.
- **A shared appointment still is not shared from LEGION's side** in the sense the notes map worried
  about - but Google now handles that, which is a straight improvement on `notes-lists-calendar`
  ticket 09's local-only outcome.
- **`ListItem`'s doc comment is now wrong** where it cites charting decision 6, and so is
  `data/local/ItemList.kt`'s neighbourhood. Correcting them is a build ticket, not a note - this repo
  has already been bitten by comments that outlived the thing they described (commit `0088e79`).
