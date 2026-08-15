# 14 - LOG tab: month calendar replaces the WEEK AHEAD strip, and the scroll regression

Status: OPEN. Kevin, 2026-08-14: "i cant scroll down anymore. the visual obscures the scroll
interface. lets make it a calendar with events on it. and then the list that currently have
below it."

## The regression (fix this FIRST, its own commit)

`ui/NotesScreen.kt`'s body is a plain `Column(Modifier.fillMaxSize())` that **does not scroll**:
title -> WEEK AHEAD strip -> MISSED -> `GoalsPanel` -> `LogModeToggle` -> `InboxScreen`/
`GroceryScreen`. Children take their intrinsic heights in order and the LAST child gets only the
remainder. Ticket 13's strip added ~230dp (180dp `DeckBarChart` + day letters + caption), so on a
real phone the inbox list's own `LazyColumn` is squeezed toward zero height - it cannot be
scrolled or even seen. Nothing "overlays" it; it is being measured out of existence.

**Fix, both parts, both required:**
1. The mode content gets `Modifier.weight(1f)`: `Box(Modifier.weight(1f)) { InboxScreen(...) }`
   (same for `GroceryScreen`). A weighted child is measured from the REMAINING space and can
   never be starved to zero by whatever the header grew into. This is the structural guarantee -
   keep it even after the calendar shrinks the header.
2. Cap the whole header region so it can never dominate a small screen: wrap title + calendar +
   MISSED + goals + toggle in a `Column(Modifier.heightIn(max = <fraction>))`? NO - a hard cap
   would clip the goals panel. Instead: the calendar itself carries a COLLAPSE affordance (see
   below), and MISSED's existing 220dp cap stays as is. Do not add other caps.

Verify the fix by reasoning about measurement, and name it in the commit: with `weight(1f)` the
list is always allocated the leftover space and scrolls internally.

## The calendar (second commit)

Replace `WeekAheadStrip` entirely (delete it and its call site; `buildWeekAheadDayCounts` and
`dayOfWeekLetter` in `ui/notes/CalendarAgendaResolver.kt` are REUSED, not deleted - see below).

### Shape

- A month grid: a `< AUGUST 2026 >` header row (same prev/next pattern as
  `ui/ledger/BudgetSection.kt`'s month navigator, including the disabled-arrow colour treatment),
  a row of seven day-of-week letters, then the weeks of that month as rows of seven cells.
- **Cell height 34dp, not more.** Six week-rows plus headers must stay under ~260dp total. The
  point of this ticket is giving height BACK to the list.
- Leading/trailing cells (days belonging to the previous/next month) render as empty slots, not
  as numbers from the neighbouring month.
- **Today's cell**: filled primary background with `onPrimary` text (the inverted-amber treatment
  `DeckTag`/`DeckRangeSelector` already use for a selected stencil chip) - state reads as a shape,
  not colour alone.
- **Selected cell**: 1dp border in primary. Distinct from today's fill so "today" and "the day I
  am looking at" are never confused.

### Events on it

- Each cell shows the day number and, beneath it, up to **three dots** for the events that day:
  1-2 events = one dot, 3-4 = two dots, 5+ = three dots. Zero events = no dots. Dots encode
  DENSITY ONLY, never source or importance - the words live in the list below (this keeps
  CLAUDE.md §4 rule 5 satisfied: nothing meaningful is carried by a glyph alone, because the
  same information is stated in words in the list).
- Counts come from the SAME merged local+Google stream ticket 13 built: `NotesController
  .timedItemsInWindow` + `allRecurringItems`/`Recurrence.occurrencesInWindow` + `CalendarProvider
  .eventsInWindow`, merged with `mergeAgenda`, bucketed with the EXISTING
  `buildWeekAheadDayCounts(entries, dayStarts)` - it already takes an arbitrary `dayStarts` list,
  so a month's worth of day-starts works unchanged. Do not write a second counting rule.
- Window = the displayed month's first day 00:00 local through its last day 23:59:59.999 local.
  Recurring expansion uses that same window.
- Calendar-not-linked: keep rendering the grid from LOCAL items (they are real), and render the
  existing `CalendarNotLinkedRow` words directly beneath it, so the grid is never silently
  presenting a partial picture as complete. This replaces ticket 13's all-or-nothing suppression.

### Interaction

- Tapping a day SELECTS it. Selecting filters the list below to that day.
  - `InboxScreen` gains one new optional parameter: `dayFilterStartMs: Long? = null`. When
    non-null it restricts the rendered stream to rows whose instant falls in that local day.
    Filter the ALREADY-BUILT row list at the render layer (the same list it builds today) -
    do NOT add a new DAO query or a second stream-building path.
  - When a filter is active the screen shows a words line above the list:
    `"showing <Fri 15 Aug> - tap SHOW ALL for everything"` with a `SHOW ALL` `TextButton` that
    clears the selection. Never a bare filtered list with no statement of what was hidden.
  - Tapping the selected day again clears the selection (same as SHOW ALL).
- The month header row carries a `MONTH`/`HIDE` text toggle that COLLAPSES the grid to nothing
  (header row only). Collapsed state is remembered for the session only (`remember`, not
  persisted). This is Kevin's direct complaint answered: the graphic can always be got out of
  the way.
- Changing month clears the day selection (a selected day from another month filtering the list
  would read as an empty list for no visible reason).

### Pure layer (testable, no Compose)

Put in `ui/notes/CalendarAgendaResolver.kt` next to the existing helpers:

```kotlin
/** One cell of the month grid. [dayStart] null = a leading/trailing blank slot. */
data class MonthCell(val dayStart: Long?, val dayOfMonth: Int?, val eventCount: Int)

/** The displayed month's cells, in rows of seven, Monday-first or Sunday-first per
 *  WeekFields.of(Locale.getDefault()) - match whatever dayOfWeekLetter already assumes. */
fun buildMonthCells(month: YearMonth, counts: Map<Long, Int>, zone: ZoneId = ZoneId.systemDefault()): List<MonthCell>

/** Dots for a count: 0 -> 0, 1..2 -> 1, 3..4 -> 2, 5+ -> 3. */
fun eventDotCount(eventCount: Int): Int
```

## Verification

- [ ] Unit tests: `buildMonthCells` (leading/trailing blanks for a month starting mid-week, cell
      count is a multiple of 7, counts land on the right day, a 31-day month spanning six week
      rows), `eventDotCount` boundaries (0/1/2/3/4/5/99).
- [ ] `compileDebugKotlin -Pnokey` + `testDebugUnitTest` green.
- [ ] No new DAO query and no second counting rule (reuses `buildWeekAheadDayCounts`) - name the
      reused functions in the commit message.
- [ ] `WeekAheadStrip` is gone; its tests that still apply (`buildWeekAheadDayCounts`,
      `dayOfWeekLetter`) still pass unchanged.
- [ ] On-device (QA): the inbox list scrolls again with the calendar shown AND collapsed; tapping
      a day filters the list and the SHOW ALL line appears; today's cell is visibly distinct from
      a selected cell.
