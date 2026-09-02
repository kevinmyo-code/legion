---
map: one-today
ticket: "02"
title: "You cannot cross off a calendar item, and the field is not what is missing"
type: build
status: open
status-detail: "Partly done 2026-09-01: appointments and one-off reminders tick through NotesController and persist across navigation, verified on the A25. RECURRING occurrences still render untickable - per-occurrence ticking is this ticket's open half."
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# You cannot cross off a calendar item, and the field is not what is missing

**Kevin, 2026-08-30:** *"i queried the voice ai, and it didnt know if a calendar item was done or
not. we need to put some kind of field for it as well."*

## The field already exists

`public.events` has `done boolean not null default false` and `done_at timestamptz`
(`20260825000400_aspect_dates_notes_merged.sql:56-57`). `data/local/Event` has `done` and `doneAt`.
Both sides, already there. **Nothing needs adding.**

## What is actually in the way

**`NotesController` - the only thing in the app that ticks anything - is filtered to
`EventKind.REMINDER` at every read and every write.** `allItems`, `theList`, the tick path, the
mutation guard at line 451 (`if (row.deleted || row.kind != EventKind.REMINDER) return null`). All
261 appointments are structurally outside the tick path, so no UI offers a checkbox for one and no
voice tool can report its state.

## Why the filter is there, and why it is still half-right

Ticket 11 added `kind` precisely so `AlarmScheduler`'s startup sweep would stop treating appointments
as reminders it owned - the 2026-08-26 incident where 51 rows were falsely marked missed and written
to the live project.

**That reasoning is sound and must survive.** But it answers *"whose alarm is this?"*, and the filter
is now also being used to answer *"can this be ticked?"* - a different question with a different
right answer. An appointment should never arm LEGION's alarm. It absolutely can be something Kevin
did.

## The decision

**Separate the two questions.** Alarm ownership stays keyed on `kind`. Tickability becomes its own
predicate, and appointments are tickable.

Open sub-questions, all real:

1. **What does `done` mean on an appointment?** "I attended" is not the same as "I completed a task".
   It may want different wording in the UI even if it is the same column.
2. **Recurring events cannot be ticked as a whole** - `events_recurring_not_done` enforces
   `repeat_kind is null or done = false`, and `event_skips` is the occurrence mechanism. An imported
   Google row is pre-expanded per occurrence so this mostly does not bite, but a LEGION recurring
   reminder does. **Ticking one occurrence of a recurring thing is a real design question, not an
   implementation detail.**
3. **Does ticking write back to Google?** `InboxScreen` already writes through for edits and
   deletions. Google has no "done" concept, so this is almost certainly local-only - but it must be
   decided rather than defaulted, because a tick that silently does not travel is exactly the class
   of thing this project keeps finding.

   **This point was read as a broader ruling than it stated, and that broader reading was REVERSED
   2026-09-02 (live-sync ticket 04, Kevin).** `notes/NotesController.kt`'s `updateAppointment`/
   `removeAppointment` (rename/delete of an appointment or task, not the tick this point is actually
   about) cited this point as authority for staying local-only on EVERY install, configured or not -
   a defensible reading at the time it was made, since nothing synced live at all yet. It became a
   real hole the moment `EventsAppointmentWriter.addEvent` started syncing CREATION (live-sync
   ticket 02): the two devices would silently diverge on exactly the edit a user is most likely to
   make next. Both now route through `EventsAppointmentWriter.updateEvent`/`.deleteEvent` - the same
   write-through-plus-outbox shape `addEvent` already uses, soft-deleting rather than hard-deleting
   so a pull that propagates tombstones (live-sync ticket 03) never resurrects a locally-hard-deleted
   row. **Ticking still stays local-only, unaffected** - Google genuinely has no "done" concept, and
   this reversal is about rename/delete only. See `memory/library/decisions.md`'s 2026-09-02 entry.
4. **The voice tools need it both ways** - report done state, and set it. Whatever `manage_item` does
   for reminders should extend, not be duplicated.

## Done means

Kevin can tick a calendar item on the phone, ask the assistant whether something is done and get a
true answer, and `AlarmScheduler` still owns exactly what it owned before.
