---
map: quant-viz
ticket: 13
title: 13 - Notes tab agenda density strip
type: ""
status: resolved
status-detail: "2026-08-16, verified built in the all-effort sweep"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# 13 - Notes tab agenda density strip

series is schedule density.

## Spec (`ui/NotesScreen.kt`, resolver in `ui/notes/`)

1. At the top of the Notes tab: a 7-day WEEK AHEAD strip - one slot per day starting today,
   rendered with the existing `DeckBarChart` (7 bars): bar value = count of agenda items that
   day (timed one-off + recurring occurrences), built from the SAME `NotesController` +
   calendar reads the tab/Today agenda already performs (`mergeAgenda` /
   `buildAgendaCalendarNotice` machinery - reuse, never a second query path).
2. **Zero is a genuine zero here, not a gap**: an empty day means "nothing scheduled", which the
   app KNOWS (unlike an unlogged meal). Bars of value 0 render as 0-height on the baseline;
   `null` slots are used ONLY when the calendar is not linked at all - in that case render the
   existing CalendarNotLinkedRow words instead of a chart of guesses.
3. Labels: day-of-week letters under each bar via `DeckBar.valueLabel`? NO - valueLabel draws
   the value; the kit draws no x labels for bars. Put the day letters in a plain stamp Row under
   the chart, 7 evenly-spaced cells aligned with the bars (simple `Row` with `weight(1f)` cells,
   no ui/common change). Today's bar gets its count as valueLabel; others unlabelled.
4. Tapping the strip does nothing in this ticket (the agenda list is right below it).

## Verification

- [ ] Pure day-count mapping unit-tested: recurring expansion counted per occurrence day,
      zero day -> 0 not null, calendar-unlinked -> no chart.
- [ ] `compileDebugKotlin -Pnokey` + `testDebugUnitTest` green.
- [ ] No ui/common change.
- [ ] On-device: Notes face shows the 7-day strip; empty week renders a flat baseline, not blank.

## VERIFIED BUILT 2026-08-16 - closed

Swept against HEAD during the all-effort verification. **Every one of this effort's 16 tickets was
built, wired to a production path, and unit-tested where it had a pure layer.** Each has a landing
commit. `MEMORY.md` was right that the effort shipped; **these `Status:` lines were simply never
flipped**, so the tracker counted 16 phantom open tickets and any frontier query was wrong.

Full per-ticket evidence is in the sweep record on `../map.md`.

### Exception on this ticket - deleted on purpose, and that is compliance

`WeekAheadStrip` returns zero matches anywhere under `app/src/`. **That is ticket 14's explicit
instruction** ("Replace `WeekAheadStrip` entirely - delete it and its call site"). Its pure layer
survived and is reused exactly as 14 required: `buildWeekAheadDayCounts` / `dayOfWeekLetter` in
`ui/notes/CalendarAgendaResolver.kt`, still tested, now feeding the month calendar
(`NotesScreen.kt:239`).

Absence here is compliance, not a regression.
