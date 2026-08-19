---
map: quant-viz
ticket: 17
title: "Two shipped visualisations vanished in a later rebuild, and nothing noticed"
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Two shipped visualisations vanished in a later rebuild, and nothing noticed

## Question

Found 2026-08-16 during the all-effort verification sweep, not by any test. Both are features that
**shipped, were QA'd on-device, and then silently disappeared** when a later effort rebuilt the
screens around them. The pure layers survived, still compute, and are still unit-tested - they just
feed nothing.

This is a different failure from anything filed so far. The other findings today were code that
never existed or docs that lagged the code. **This is code that existed, worked, was verified on a
phone, and was removed by accident.**

### Regression 1 - the maintenance due meter

- Built by quant-viz ticket 05 Part C, commit **`7c6a5ca`**, which added
  `DeckMeter(row.fraction, ...)` to `FleetScreen`.
- **Dropped by the mission-control rebuild, commit `a09aa68`.**
- What remains: `DueRowView.fraction` (`ui/fleet/FleetRows.kt:111`, computed `:311`) and
  `ScheduleRowView.fraction` (`:378`, `:417`) are **orphaned data with no renderer**. `dueFraction`
  is still pure and still unit-tested (`FleetRowsTest.kt:147-187`).
- Repo-wide `DeckMeter` callers today: `GoalsPanel`, `BudgetSection`, `TodayScreen`, `ThemePreview`.
  Fleet is not among them.

### Regression 2 - the DRIVES miles sparkline, plus a comment that lies about it

- `buildMilesSparkline` (`ui/fleet/FleetRows.kt:947`) is computed into
  `FleetUiState.milesSparkline` (`FleetScreen.kt:504`, field `:281`) and **read by no composable**.
  The DRIVES tile renders MPG only (`:833-842`).
- **The comment at `FleetScreen.kt:824-826` claims "DRIVES' own drilldown still carries both series
  in full". It does not.** `DriveHistoryDrilldownScreen` (`FleetDrilldowns.kt:994-1029`) has no
  chart at all. So the code actively misleads the next reader about where the series went.
- There is no test for `buildMilesSparkline`, while `buildMpgSparkline` has one - so nothing would
  have caught the loss even in principle.

### A third, lesser one for context

quant-viz ticket 11's **SLEEP sparkline** left Today when SYSTEMS SWEEP was dissolved by the same
commit `a09aa68`. That series now renders on `ui/BodyScreen.kt:439-440`, so it is **relocated, not
lost** - but ticket 11's own stated requirement no longer holds, and nobody recorded the move.

## Decide

1. **Restore, or accept the loss?** The due meter is a real capability - it is how a maintenance item
   shows how close it is without reading a number, and Kevin's standing glanceable ruling ("im not
   gonna read numbers. it has to be glancable") argues for restoring it. The miles sparkline is a
   weaker case: the DRIVES tile may be better with one series than two.
2. **If not restoring, delete the orphans.** `DueRowView.fraction`, `ScheduleRowView.fraction` and
   `buildMilesSparkline` should not sit computed-and-unread with passing tests implying they work.
   That is the pattern that produced six other orphans found today.
3. **Fix the lying comment regardless.** `FleetScreen.kt:824-826` must not claim a drilldown carries
   a chart it does not have. That is true whichever way 1 and 2 go.
4. **How does a rebuild avoid dropping a shipped feature next time?** This is the real question. A
   screen rebuild replaced composables wholesale and the only survivors were the pure functions, so
   the tests all stayed green while the UI lost two features. Options worth arguing: an inventory
   check in a ship pass, a test that asserts a state field is read, or simply accepting that screen
   rebuilds need a before/after screenshot pass - **which is how these were found in the first
   place**, several days late.
5. **Is anything else missing?** Two were found by auditing 16 tickets. The same rebuild touched
   HOME, CRED, FLEET and NOTES. Nobody has checked the rest.
