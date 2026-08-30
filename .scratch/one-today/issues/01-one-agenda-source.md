---
type: decision
status: open
blocked_by: []
map: one-today
---

# One agenda source, and it is the server's

Every calendar surface derives its own window today. The audit counted **four independent live
`CalendarContract` fetches** - HOME's hero, LOG's today pane, LOG's month grid, and `InboxScreen` -
plus `SitrepBuilder`'s. Each merges in `events` where `kind='reminder'` separately.

Meanwhile `CalendarImportController` imports Google into `events` as `kind='appointment'` on every
process start (-30d/+180d), producing **261 rows that no screen reads.**

## The decision

**Every renderer reads one agenda query, and that query reads the server-backed `events` table.**

`engine/dates/DatesAgenda` already describes itself as *"THE agenda source... agenda is a query,
across the Dates aspect plus every record's dueAt column - one fact, one place"* and has **zero UI
callers** - only the alarm scheduler and the spoken opener. It is the thing to point at, not a new
thing to write beside it.

## Why the server path, not the live one

**The PC cannot query `CalendarContract`.** ADR 0040 makes the web app the general client; it sees
only Supabase. A phone reading Android's calendar directly and a laptop reading `events` are two
calendars that will disagree, and the disagreement will be silent.

Kevin, same day: *"everything runs from the supabase backend."*

## The cost, stated plainly so it is a choice and not a surprise

**A calendar edit made outside LEGION appears on the next import, not instantly.** Today the phone
sees it immediately because it queries Android live.

Mitigations, in order of cost: import on resume as well as process start; import after the
`CalendarProvider` change notification fires; or accept the lag. **Decide which before building** -
"we will tune it later" is how a laggy calendar becomes the reason someone stops trusting the app.

## What must not break

- **`AlarmScheduler`'s reminder-only sweep.** Ticket 11's `kind` discriminator exists because a sweep
  over the merged table marked 51 rows falsely missed. Whatever reads the agenda for DISPLAY must not
  widen what the alarm path treats as its own.
- **`InboxScreen`'s write-through to Google.** It is the only surface that edits Google events
  (`CalendarProvider.updateEventSeries`/`deleteEventOccurrence`, with occurrence-vs-series scope).
  Reading from `events` must not silently turn those edits into local-only ones.
- **`CalendarAgendaResolver`** is the one piece of calendar logic with no duplicate - `mergeAgenda`,
  `buildMonthCells`, `eventDotCount`, `agendaDayStart`'s UTC-vs-device-zone all-day fix, fully unit
  tested with no Android types. **Keep it.** It is the merge logic, not the fetch, and it survives.

## Done means

One fetch per window, one code path, and `DatesAgenda` is what the screens call. The 261 imported
appointments become visible for the first time. Deleting the redundant fetches is the proof it
worked, not a follow-up.
