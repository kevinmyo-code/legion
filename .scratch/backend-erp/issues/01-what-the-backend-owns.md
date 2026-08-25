---
map: backend-erp
ticket: "01"
title: "What the backend owns: schema, truth, and the phone's residual role"
type: grilling
status: resolved
status-detail: "All 5 questions ruled; per-aspect typed tables, engine retires, sequencing moves to ticket 05"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# What the backend owns: schema, truth, and the phone's residual role

## Question

The root ticket. Decide:

1. The Postgres schema: the engine's four generic tables translated (records as JSONB payload plus
   promoted columns, mirroring Room v37), or per-aspect real tables now that Postgres does DDL
   properly? Recommend the generic shape moves as-is: it is proven, and the metadata layer IS the
   product.
2. What stays phone-only: OBD-live state, wake word, photo files?
3. Room's new role: consumer cache with what freshness contract; which reads may hit the network
   synchronously ("always online") vs cache-first.
4. Writes: the phone writes to Supabase directly (PostgREST) with RecordStore becoming a client of
   the API, or offline-queue-and-push?
5. What of the engine's enforcement (references, delete policies, computed fields) moves into
   Postgres (FKs, triggers, RLS) vs stays client-side vs both.

## Resolution (2026-08-25) - eleven rulings

Rulings so far, Kevin's answers in order:

1. **Supabase is the ONE master.** The calendar-projection idea (todos projected into Google
   Calendar) was chosen first, then SUPERSEDED by a sharper ruling: **LEGION keeps its OWN
   calendar** - the Dates aspect moves to Supabase with everything else, every consumer renders
   its own calendar view over the existing agenda query, and Google Calendar demotes to the
   already-built one-way import feed. The LEGION::v1 description blocks (merged 2026-08-25) stay
   useful read-side for imported events only; there is no projection.
2. **Undated todos get due=tomorrow AS AN INFERRED FACT** - tagged source:inferred, rendered as
   "showing tomorrow (no date set)", rolls forward silently, NEVER goes overdue or nags. Only a
   stated date may nag. (The provenance discipline applied to defaults.)
3. Calendar-delete semantics: DISSOLVED - Kevin will not edit or delete in Google Calendar, and
   with the own-calendar ruling there is no projection to edit.

4. **Todos become Dates EVENTS (Kevin, 2026-08-25).** Option B, chosen over the recommended
   option A (Notes records with `dueAt` surfaced through the agenda query). A due thing is a dated
   thing; there is one dated record type, not two. **The cost was stated and accepted:** recurrence,
   skips, place-triggers and tick/completion history all get rebuilt on the event type, and
   existing Note-shaped todos need a migration. Recommendation is recorded as overruled, not as
   unheard - see the cost inventory below before scheduling the work.
5. **Google Calendar is DROPPED ENTIRELY (Kevin, 2026-08-25).** Not demoted to an import feed -
   removed. This supersedes the "Google demotes to the already-built one-way import feed" half of
   ruling 1 above; LEGION's own calendar is the only calendar. **Accepted cost:** anything that
   arrived only by import (class schedules being the known case) becomes manual entry, and the
   read-side value of the LEGION::v1 description blocks for imported events goes with it. The
   blocks' machine-fields-reach-the-model / prose-does-not discipline survives as a pattern; its
   only consumer does not.

6. **Postgres gets PER-ASPECT REAL TABLES (Kevin, 2026-08-25).** Answers ticket question 1, and
   with it question 5. Chosen over the recommended "move the generic shape as-is" and over the
   generic-plus-references-side-table middle. The reasoning that won: Postgres can only enforce
   what it has real columns for, so a jsonb payload permanently caps referential integrity,
   CHECKs and triggers at whatever the client remembers to do. Typed tables put enforcement where
   it cannot be bypassed by a future consumer surface - which is the whole point of a backend that
   several clients will write to. **Accepted cost:** field defs become migrations, a new aspect
   needs a deploy rather than a metadata row, and the metadata layer stops being the product.
7. **The phone goes typed too; the generic engine RETIRES (Kevin, 2026-08-25).** The follow-on
   fork created by ruling 6: with typed tables server-side, either a per-aspect mapper translates
   to the phone's generic shape, or both ends share one shape. Kevin chose one shape end to end.
   Room mirrors the per-aspect tables; `records`/`record_types`/`field_defs`, the generated forms,
   the generated validation and the computed-field machinery all retire with it. **Accepted cost,
   and it is the largest in this map:** this undoes the 2026-08-24 engine cutover that made fleet,
   ledger and pantry engine-native one day earlier, and adding an aspect becomes a Room migration
   plus a Postgres migration plus hand-written UI. A measured footprint inventory is being
   compiled before any of this is scheduled - see "Cost inventory" below. **Nothing is deleted
   until ticket 05 (migration path) says in what order**; a retirement with no sequenced path is
   how data gets lost.

8. **Write path: DIRECT WRITE, no local queue (Kevin, 2026-08-25).** Chosen over the recommended
   offline-queue-and-push. Writes go straight to Postgres via PostgREST/RPC; a failed write is
   reported as failed rather than silently held. **This coheres with ruling 9** - the server is the
   only writer of truth, so the gate runs in exactly one place and no queued row can ever bypass
   it. **Accepted cost:** no offline capture at all. A dictated note with no signal is not saved,
   and the free tier's 7-day inactivity pause (research ticket 06) becomes a hard outage rather
   than a degraded mode. The keep-alive from ticket 06 stops being a nicety and becomes load-
   bearing; say so in ticket 05.
9. **Read path: CACHE-FIRST, refresh in background (Kevin, 2026-08-25).** Room renders immediately,
   the network reconciles behind it. Consequence to honour, not a cost to accept: a money figure or
   a reconciliation total can be briefly stale, and CLAUDE.md §4 rule 5 plus the outcome-verb rule
   mean a stale figure must carry a visible "as of", never be shown bare. Room is therefore written
   on server ACK, never ahead of it - which is the same door discipline `RecordStore` has today.
10. **Phone-only residue (Kevin, 2026-08-25), answering ticket question 2.** Four things never
    leave the device: OBD live state (ephemeral, high-frequency, meaningless to a laptop - trips
    and maintenance still sync as records); wake word and any raw audio buffer; photo files (only
    the extracted records sync, so the laptop surface cannot show a receipt image); widget layouts
    and per-device dismissals (`widget_instances`, `muted_reminders` - already deliberately local,
    and `EngineRecord`'s own doc comment gives the reason).
11. **Google exit is a WIDENING ONE-TIME IMPORT, then the cut (Kevin, 2026-08-25).** Ruling 5 drops
    Google; this rules how. Measured hazard that forced the question: the `LEGION::v1` description
    blocks carrying class metadata (`course`, `source: canvas_verified`, `conflict`, `status`) are
    authored IN Google Calendar and never by LEGION - nothing in `app/src/main` writes an
    `Events.DESCRIPTION`. They reach exactly one surface, live and unstored: the `read_calendar`
    voice tool (`service/LiveToolbox.kt:3114`, the only consumer of
    `CalendarReadToolLogic.structuredBlock`). `CalendarImportController.kt:117-123` persists only
    title/start/end/source/googleEventId, dropping description, location and `allDay`, over a
    window of only `now-30d .. now+180d`. Cutting Google first would delete that metadata
    permanently. **Order is binding:** widen the Dates `Event` type with description/location/
    allDay fields, parse the `LEGION::v1` block into real fields, run one import over an unbounded
    window, verify, and only then remove the Google path. Side benefit: the metadata finally
    reaches screens and widgets instead of only the voice tool.

## Cost inventory (measured 2026-08-25, `traced` unless noted - nothing built or run)

**Todos are not a record type.** They are the Notes aspect's single `Item` type -
`engine/notes/NotesAspectSeeder.kt:32-70`, 21 fields, one flat shape covering checklist entry,
note line, time reminder and place reminder, distinguished only by which optional fields carry
values. `Item.startsAt` is wired as `RecordType.primaryDueDateFieldId` (`:154-155`) and that is
the only reason a todo appears in the cross-aspect agenda at all. The Dates `Event` type has 7
fields and **no `allDay`**. Ruling 4 is therefore a type merge, not a re-parenting.

What is attached to `Item` and has to survive the merge:
- Recurrence: `notes/Recurrence.kt` (rules :21-49, `occurrencesInWindow` :113), `NextOccurrence.kt`,
  `RepeatKind.kt`. Occurrences are computed on read, never materialized.
- Skips: `list_item_skips` (`data/local/ListItemSkip.kt`) - **the one legacy table still written**,
  via `NotesController.skipOccurrence:401`.
- Completion: there is **no history table**. `done`/`doneAt` on the record, plus `loggedAt` for the
  goal-sweep. Cheaper to move than the ticket assumed.
- Alarms: a per-item stack (`notes/AlarmScheduler.kt`, `service/ReminderAlarmReceiver.kt`) that
  **shares no code** with the engine's one-alarm-total Dates stack (`service/DatesAlarmScheduler.kt`,
  see its own :11-15). Muting (`muted_reminders`) is honoured by the Dates path only (`reasoned`).
- Place triggers: `Item.triggerPlaceLabel` -> `location/ReminderController.kt` ->
  `location/GeofenceManager.kt`, keyed on `TaggedPlace` label as the geofence `requestId`.
- Goal plans: `advisor/GoalChecklistSync.kt` materializes checklist items per plan line per day.

Blast radius: **64 Kotlin files** reference the todo model (49 main + 15 test), 34 of them
importing `NotesController` directly; **12 UI files**; **4 of the 104 `LiveToolbox` tools**
(`manage_item`, `read_list`, `set_reminder`, `read_calendar`). Counts are grep-mechanical and may
over-count doc-comment-only hits.

**Three agenda paths exist, not one**, and they are mutually disjoint - this is pre-existing debt
the merge should collapse rather than inherit: `engine/dates/DatesAgenda.kt` (scans every
`dueAt`, honours mutes, **no UI reads it**), `ui/notes/CalendarAgendaResolver.kt` (what the screens
actually use - unions `NotesController` output with live Google events), and
`engine/WidgetDataSource.kt:118-152` (a third independent `dueAt` scan that **ignores mutes**).

**`CalendarProvider.kt` is not import-only** - it also writes to Google (`insertEvent:267`,
`updateEventSeries:307`, `updateEventOccurrence:345`, `deleteEventSeries:379`,
`deleteEventOccurrence:400`), used by the `addAppointment` tool and `ui/notes/InboxScreen.kt`.
Ruling 5 removes those write surfaces too. **It has zero test coverage** - every
`ContentResolver` query and all six write functions are untested. Import runs on app foreground
only; there is no background worker.

**All five of the ticket's original questions are now answered**: 1 and 5 by rulings 6-7
(per-aspect typed tables, enforcement server-side in real FKs/CHECKs/RLS), 2 by ruling 10, 3 by
ruling 9, 4 by ruling 8. What remains is not grilling but sequencing, and it belongs to ticket 05:
the order of the engine retirement, the Item/Event type merge, and the Google exit import. Related context landed the same day: the llm-wiki
notes research (research/wiki-notes-second-brain.md) and cross-interface memory - both in the
map's Not yet specified.
