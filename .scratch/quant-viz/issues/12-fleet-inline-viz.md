---
map: quant-viz
ticket: 12
title: 12 - Fleet tab inline additions
type: ""
status: resolved
status-detail: "2026-08-16, verified built in the all-effort sweep"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# 12 - Fleet tab inline additions

MAINTENANCE due meters (ticket 05); two additions to the tab FACE.

## Spec (`ui/FleetScreen.kt`, `ui/fleet/FleetRows.kt`)

1. **RECAPS row -> RECAPS pane strip:** where the recap count renders, add a `DeckSparkline` of
   `MonthlyRecap.milesDriven` across all recaps (already in `FleetUiState.monthlyRecaps` since
   ticket 05), oldest first, one point per recap month with `null` gaps for skipped months
   (reuse ticket 05's month-slot builder - `buildRecapMonthSlots`/`recapMonthPoints`, do not
   reimplement). With fewer than 2 recaps, keep the count row only (the drilldown's own
   "trend appears after two monthly recaps" posture, stated in the same words here).
2. **DRIVES pane:** add a second sparkline under the MPG one: daily `DailyDriveLog.milesDriven`
   from the SAME rows `buildMpgSparkline` already receives (`ui/fleet/FleetRows.kt:209` area) -
   a `buildMilesSparkline` sibling, same gap rule, unit-tested next to the existing MPG test if
   one exists (add both cases if not).
3. Labels: each sparkline gets a one-word faint stamp caption (`"mpg"`, `"miles"`) so two
   adjacent silhouettes are never ambiguous - words, not position alone.

## Verification

- [ ] `buildMilesSparkline` (and month-slot reuse) unit-tested: gap day/month -> null, ordering.
- [ ] `compileDebugKotlin -Pnokey` + `testDebugUnitTest` green.
- [ ] No new DB queries (both series from already-loaded state - trace it in the commit message).
- [ ] On-device: Fleet face shows MPG + miles sparklines; RECAPS strip appears only when >= 2
      recaps exist (Kevin has 1 - expect the count + sentence today).

## VERIFIED BUILT 2026-08-16 - closed

Swept against HEAD during the all-effort verification. **Every one of this effort's 16 tickets was
built, wired to a production path, and unit-tested where it had a pure layer.** Each has a landing
commit. `MEMORY.md` was right that the effort shipped; **these `Status:` lines were simply never
flipped**, so the tracker counted 16 phantom open tickets and any frontier query was wrong.

Full per-ticket evidence is in the sweep record on `../map.md`.

### Exception on this ticket - the miles sparkline is orphaned AND a code comment lies about it

The RECAPS strip is built and wired (`FleetDrilldowns.kt:224-236`), one tap in.

**`buildMilesSparkline` (`ui/fleet/FleetRows.kt:947`) is computed into `FleetUiState.milesSparkline`
(`FleetScreen.kt:504`, field `:281`) and read by NO composable.** The DRIVES tile renders MPG only
(`:833-842`).

**Worse, the comment at `FleetScreen.kt:824-826` claims "DRIVES' own drilldown still carries both
series in full". That is false** - `DriveHistoryDrilldownScreen` (`FleetDrilldowns.kt:994-1029`) has
no chart at all. There is also no test for `buildMilesSparkline`, while `buildMpgSparkline` has one.

Filed in [the silent-regression ticket](17-silent-regressions.md).
