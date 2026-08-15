# 12 - Fleet tab inline additions

Status: OPEN. Kevin's glanceable ruling. Fleet already has the DRIVES MPG sparkline and the
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
