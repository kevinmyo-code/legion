---
map: quant-viz
ticket: 16
title: 16 - Tapping a calendar day pops up what is on that day
type: ""
status: resolved
status-detail: "2026-08-16, verified built in the all-effort sweep"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# 16 - Tapping a calendar day pops up what is on that day

should pop up a UI showing things due on that date."

## Why the current behaviour reads as broken

Tapping a day applies a filter to the inbox list BELOW the calendar. That list starts below the
fold, so from Kevin's viewpoint the tap does nothing visible - he has to know to scroll before the
result appears. A dot says "something is here"; the answer has to arrive where he tapped.

## The one design win to protect

`ui/NotesScreen.kt`'s month load already builds `merged: List<AgendaEntry>` for the whole month
and derives the dot counts from it (`buildWeekAheadDayCounts(merged, dayStarts, zone)`).
**The popup renders from that SAME `merged` list.** Not a new fetch, not a second stream: the dots
and the popup are then two renderings of one list and can never disagree. This is the structural
answer to the false-empty bug QA found last pass (dots promised events the list could not show) -
make it impossible by construction, not by a second window that has to be kept in sync.

## Spec

### Pure layer (`ui/notes/CalendarAgendaResolver.kt`, next to the existing helpers)

```kotlin
/**
 * [entries] falling on the local day starting at [dayStart], ordered for display:
 * all-day entries first (they have no meaningful time), then timed entries ascending.
 * Ties keep their incoming relative order (stable sort) so a rebuild never reshuffles rows.
 */
fun entriesForDay(entries: List<AgendaEntry>, dayStart: Long, zone: ZoneId = ZoneId.systemDefault()): List<AgendaEntry>
```

**Invariant to hold and to test:** `entriesForDay(merged, d).size` equals the count
`buildWeekAheadDayCounts(merged, listOf(d))` produces for that same day. Both bucket by
`dayStartEpoch`; do not invent a second day-bucketing rule here, reuse that function.

### State (`ui/NotesScreen.kt`)

- Keep the month's `merged` list in state alongside `monthCells` (e.g. `monthEntries`). It is
  already computed in the same `LaunchedEffect` - store it, do not re-derive it.
- New `var popupDayStart by remember { mutableStateOf<Long?>(null) }`. Tapping an in-month day
  cell sets it. Leading/trailing blank cells stay untappable.
- Changing month closes the popup (same reasoning ticket 14 used for clearing day selection).

### The dialog (`ui/notes/NotesRows.kt` or a new `DayEventsDialog.kt`)

- **Use `AlertDialog`**, not a bottom sheet: every existing modal in this app is an `AlertDialog`
  (`CarRows.AddCarDialog`, `CompanionRows.CompanionEditorDialog`, `GoalsPanel.GoalEditDialog`) and
  matching the house pattern beats introducing a second modal idiom for one screen.
- Title: the full date, e.g. `"Friday 7 August"`.
- Body: one row per entry -
  - left: `clockTime(entry.timeMs)` (reuse `util/Dates.kt`'s existing formatter, the same one
    `ui/TodayScreen.kt`'s AGENDA rows use), or the word `ALL DAY` for `entry.allDay`;
  - right: `entry.label`, wrapping rather than truncating (this is the detail view - a title that
    gets clipped here has nowhere else to be read);
  - a `CAL` `DeckTag` on `AgendaSource.GOOGLE` rows only, exactly as `TodayScreen`'s `AgendaRow`
    already distinguishes them - **in words, never colour alone** (CLAUDE.md §4 rule 5).
- Many entries: the body scrolls (`LazyColumn` with a bounded `heightIn(max = 320.dp)`) so a busy
  day cannot push the buttons off screen.
- Empty day (a day with no dots is still tappable): the body reads
  `"Nothing on this day."` - an honest empty, and it can only ever appear when the same list that
  drew zero dots is genuinely empty.
- Buttons:
  - confirm: `SHOW IN LIST` - sets the existing `selectedDayStart` day filter, closes the popup.
    Keeps ticket 14's filter feature (QA-verified, do not delete it) as the way to act on rows.
  - dismiss: `CLOSE`.

### Do not change

Day-filter behaviour, SHOW ALL, the collapse toggle, month arrows, dot rules, the single scroll
surface (ticket 15). This ticket ADDS a popup; it removes nothing.

## Verification

- [ ] Unit tests for `entriesForDay`: all-day entries sort first, timed ascending, entries on
      neighbouring days excluded, an empty day returns empty, and the size-equals-dot-count
      invariant above holds for a fixture month.
- [ ] `compileDebugKotlin -Pnokey` + `testDebugUnitTest` green.
- [ ] No new DB/Calendar query added (popup reads the already-loaded month list) - name the reused
      state in the commit message.
- [ ] On-device (QA): tapping a dotted day pops the dialog listing that day's entries, the row
      count matches the dots drawn on that cell, a Google row shows `CAL`, `SHOW IN LIST` filters
      the list below and closes, `CLOSE` dismisses, and an undotted day says "Nothing on this day."

## VERIFIED BUILT 2026-08-16 - closed

Swept against HEAD during the all-effort verification. **Every one of this effort's 16 tickets was
built, wired to a production path, and unit-tested where it had a pure layer.** Each has a landing
commit. `MEMORY.md` was right that the effort shipped; **these `Status:` lines were simply never
flipped**, so the tracker counted 16 phantom open tickets and any frontier query was wrong.

Full per-ticket evidence is in the sweep record on `../map.md`.
