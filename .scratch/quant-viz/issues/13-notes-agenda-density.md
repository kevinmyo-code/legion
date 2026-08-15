# 13 - Notes tab agenda density strip

Status: OPEN. Kevin's glanceable ruling: every tab face carries inline viz. Notes' only real
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
