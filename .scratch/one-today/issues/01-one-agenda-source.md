---
type: build
status: open
blocked_by: []
map: one-today
---

# Cut Google entirely. One calendar, and it is ours.

**Kevin, 2026-08-30:** *"cut everything that was from google. everything CRUD to supabase."*

This executes backend-erp ticket 01 **ruling 5** (2026-08-25): *"Google Calendar is DROPPED
ENTIRELY. Not demoted to an import feed - removed. LEGION's own calendar is the only calendar."*

## The binding order is satisfied - verified, not assumed

Ruling 5 came with a condition: **widen the importer first, verify, THEN cut**, because class
metadata lived only in Google descriptions and was never stored, so cutting first would destroy it.

The widened importer (`6e36ef1`, 2026-08-27) ran. Checked against the real device snapshot:

| | |
|---|---|
| `kind='appointment'` rows | **261** |
| with `allDay` | 261 |
| with `notes` (the prose half of `description`) | 222 |
| with `location` | 104 |
| with `structuredMeta` - the `LEGION::v1` blocks | **5** |
| with `googleEventId` | 261 |

**The class metadata is captured.** The rows are complete and already synced to Supabase. The
condition is met and the cut is safe.

## What this ticket actually is

Not a migration. **The data has already moved.** This is deleting the borrowed pipe and letting the
rows that came through it become ordinary LEGION rows.

### Keep

The 261 rows. They are real, complete, on the server, and are now simply events. `googleEventId`
stays as provenance - it records where a row came from, the same way `origin_guid` does elsewhere,
and costs nothing.

### Cut

- `calendar/CalendarProvider.kt` and every live `CalendarContract` query. The audit found **four
  independent fetches** - HOME's hero, LOG's today pane, LOG's month grid, `InboxScreen` - plus
  `SitrepBuilder`'s.
- `calendar/CalendarImportController` and its process-start import.
- **`InboxScreen`'s write-through to Google** (`updateEventSeries`/`deleteEventOccurrence` and
  `CalendarEditResolver`'s occurrence-vs-series scope). This is the sharpest deletion: it is the only
  place LEGION writes to Google, and after the cut an edit there is a local edit. **Check nothing
  still routes through it before deleting.**
- The `read_calendar` voice tool's live path and `CalendarReadToolLogic.structuredBlock`'s only
  consumer - but see below, the blocks themselves are now stored.
- `SitrepBuilder`'s CALENDAR module's live query; it reads `events` instead.
- The `READ_CALENDAR` permission and any Google Calendar scope, once nothing needs them.

### Repoint

Everything that read Google now reads `events`. `engine/dates/DatesAgenda` already describes itself
as *"THE agenda source... one fact, one place"* and has **zero UI callers** - point the screens at
it rather than writing a second query beside it.

`ui/notes/CalendarAgendaResolver` **survives untouched** - `mergeAgenda`, `buildMonthCells`,
`eventDotCount`, `agendaDayStart`'s UTC-vs-device-zone all-day fix. It is merge logic with no Android
types and no duplicate. The fetch changes; the merge does not.

## What is genuinely lost, stated so it is a choice

**Anything that only ever arrived by import becomes manual entry** - class schedules are the known
case, and ruling 5 accepted this in writing. Concretely: a new semester's timetable is typed in, or
comes from wherever it originally came from, rather than appearing.

**Google's own notifications for those events stop mattering to LEGION.** LEGION arms its own alarms
off `events`, which it already does - but a reminder Kevin set inside Google Calendar is not LEGION's
to fire.

## What must not break

- **`AlarmScheduler`'s reminder-only sweep.** The `kind` discriminator exists because a sweep over the
  merged table marked 51 rows falsely missed. Widening what screens READ must not widen what the
  alarm path OWNS.
- **The 5 `structuredMeta` rows.** They are the class blocks and the only reason the cut waited.
  Whatever renders them must keep working after `CalendarReadToolLogic`'s live path goes - the blocks
  are stored now, so the parsing moves to a read of the column rather than of a Google description.

## Done means

One fetch, one path, one calendar. The 261 appointments are visible on a screen for the first time.
Every calendar write goes to Supabase, so the phone and the web app see the same thing by
construction rather than by luck. Deleting the redundant fetches is the proof, not a follow-up.
