# 15 - LOG tab: ONE scroll surface, and the false-empty past day

Status: OPEN. **Ticket 14's scroll fix did not work.** QA on-device 2026-08-14: with the calendar
expanded the inbox list is still unreachable; only collapsing the calendar restores scrolling.
Kevin's original complaint reproduces. This ticket replaces that fix with the correct one.

## Why 14 failed

`Modifier.weight(1f)` guarantees the list is not measured to ZERO. It does not give it usable
height. The LOG tab stacks FOUR non-scrolling headers before any scrollable content:

| Region | Owner | Rough height |
|---|---|---|
| `NOTES` title | `ui/NotesScreen.kt` | ~40dp |
| Month calendar | `ui/NotesScreen.kt` | ~260dp |
| MISSED (own nested LazyColumn, capped) | `ui/NotesScreen.kt` | up to 220dp |
| `GoalsPanel` | `ui/NotesScreen.kt` | ~80dp+ |
| `LogModeToggle` | `ui/NotesScreen.kt` | ~36dp |
| `ListsDoNotSyncNote` + hairline | `ui/notes/InboxScreen.kt` | ~50dp |
| Add-item row (two `OutlinedTextField`s) | `ui/notes/InboxScreen.kt` | ~80dp |

Only AFTER all of that does `InboxContent`'s `LazyColumn` begin. On the Oppo A17k (~948dp tall)
the sum exceeds the screen, so the list starts below the fold. **The bug is architectural: the
screen has one scrollable region buried under ~770dp of fixed furniture.** No amount of shrinking
the calendar fixes the shape; it only postpones it (add one goal, one MISSED row, and it returns).

## The fix: the LazyColumn becomes the screen's ONLY scroll surface

Everything scrolls together. The calendar scrolls away when Kevin scrolls down, which is exactly
what "the visual obscures the scroll interface" is asking for.

1. **`InboxContent` takes a header slot**, per the repo's vendored `compose-slot-api-pattern`
   skill:
   ```kotlin
   fun InboxContent(
       // ...existing params unchanged...
       header: (LazyListScope.() -> Unit) = {},
   )
   ```
   Its `LazyColumn` emits, in order: `header()`, then its OWN former fixed furniture as items
   (`item { ListsDoNotSyncNote(); DashedHairline() }`, `item { <add-item row> }`,
   `item { <date-unreadable warning> }`), then the day-filter row, then the existing rows.
   The `Surface`/`Column` wrapper around it goes away - the `LazyColumn` is the root.
2. **`NotesScreen` passes its furniture INTO that slot** as items, in the current visual order:
   title, calendar, MISSED, `GoalsPanel`, `LogModeToggle`. Nothing above the LazyColumn remains.
   Delete the ticket-14 `Box(Modifier.weight(1f))` wrapper - it is now meaningless.
3. **MISSED loses its nested LazyColumn.** A same-direction scrollable inside a scrollable is the
   thing that made this hard to reason about. Emit at most **four** missed rows as plain items in
   the outer list, followed by `"+ N more"` in `LegionType.stamp` when there are more. Keep every
   existing row action (open/dismiss) and the existing `DeckPane(header = "MISSED", ...)` count.
4. **GROCERY mode**: the calendar and `GoalsPanel` are ITEMS-mode furniture. In GROCERY mode the
   header is title + toggle only (a shopping list is a fast in-and-out surface), and
   `GroceryScreen` keeps its current structure untouched. State this in the commit message - it
   is a deliberate call, not an oversight.
5. Calendar collapse state, day selection, month navigation: all unchanged from ticket 14.

**Do not** wrap anything in `Modifier.verticalScroll` - a `LazyColumn` inside a vertically
scrollable parent gets infinite height constraints and throws.

## Second bug: a dotted PAST day filters to "Nothing here yet"

QA reproduced on Aug 7 2026: the calendar drew three dots (5+ events), tapping it showed
`"Nothing here yet"`. Cause: `MonthCalendar` counts a whole-month window (past and future), but
`InboxScreen`'s Google fetch is a **90-day forward-only** window from now
(`INBOX_CALENDAR_WINDOW_DAYS`), so a past day's events are never in the list the filter reads.
**The calendar promises something the list then denies.** Same shape as CLAUDE.md §4 rule 6: a
surface reading "empty/complete" when the data was never fetched.

Fix: when `dayFilterStartMs` is non-null, the fetch window must COVER that day.
- Local items (`NotesController.timedItemsInWindow`) and Google events
  (`CalendarProvider.eventsInWindow`) both fetch `[selectedDayStart, selectedDayEnd]` when a day
  filter is active, in addition to (not instead of) the normal forward window. Recurring expansion
  uses the same widened window.
- Re-fetch keyed on `dayFilterStartMs` changing.
- The empty state must now be TRUE when shown. If a day genuinely has nothing, `"Nothing here
  yet"` is correct and fine. It must never appear for a day whose data simply was not loaded.

## Verification

- [ ] Any new/changed pure function unit-tested; existing Notes tests still green.
- [ ] `compileDebugKotlin -Pnokey` + `testDebugUnitTest` green.
- [ ] No `Modifier.verticalScroll` wrapping any `LazyColumn`; exactly ONE scrollable region in
      ITEMS mode (state it in the commit message).
- [ ] Selecting a past day with dots lists that day's events, not `"Nothing here yet"`.
- [ ] On-device (QA, BLOCKING): the list scrolls with the calendar EXPANDED, on a fresh launch,
      without collapsing anything. The calendar scrolls up out of view as Kevin scrolls. This is
      the checkbox that ticket 14 failed - it is the pass/fail of this ticket.
