# 05 - Fleet recap trends, maintenance due meters, wrapped surface

Status: resolved (2026-08-16, verified built in the all-effort sweep)

Three write-only or text-only fleet datasets get read surfaces. All additive.

## Part A: recap trend drilldown

- `MonthlyRecapDao.getAll(vehicleId)` (`data/local/MonthlyRecapDao.kt:31`) already returns every
  recap; UI shows only a count today (`FleetScreen.kt:206`).
- New drilldown in `ui/fleet/FleetDrilldowns.kt`, entered from the existing RECAPS row (follow
  the file's existing drilldown pattern), showing, in order:
  1. `DeckLineChart` - miles driven per month. Series: one slot per calendar month from first to
     latest recap (`dailyBuckets` is days-only; build month slots locally - a missing month is a
     `null` GAP slot, never 0). `yLabel = { "%.0f mi".format(it) }`, xLabels thinned to Jan/Jul.
  2. `DeckLineChart` - avgMpg per month; recaps with `avgMpg == null` are gaps.
  3. A `DeckRow` list of recaps newest-first: value = `"%.0f mi".format(milesDriven)`, sub-line
     with driveCount / codeEventCount / serviceCount. `notable == true` rows get a `DeckTag`.
     Tapping a row may show `narrative` in a plain text block (read-only).
- Two recaps minimum for the charts; below that render the list only plus the sentence
  `"trend appears after two monthly recaps"`.

## Part B: `YearlyWrapped` surface

- `YearlyWrappedDao` latest row (read the DAO for the accessor; add a `getLatest` mirroring
  `MonthlyRecapDao`'s ONLY if none exists). Rendered at the TOP of the same recap drilldown as a
  `DeckPane` of text `DeckRow`s: YEAR, MILES, DRIVES, AVG MPG (null -> "-"), LONGEST, CODES,
  SERVICES, then `narrative` as reading text. Absent -> omit the pane entirely (a yearly recap
  that never generated is not a gap to announce - it generates in December).

## Part C: maintenance due meters

- `buildDueRows` (`ui/fleet/FleetRows.kt:99`) gains a computed `fraction: Float?` on
  `DueRowView`: elapsed/interval on the SAME axis the row already chose for its headline value
  (miles when miles-anchored, else time). `null` when the item has no interval - no meter drawn.
  Overdue rows: fraction 1f; the existing `overdue` flag and wording stay the only alarm.
- `DueRowView` render site (`FleetScreen.kt:491` area): `DeckMeter(fraction)` under the row's
  text when fraction != null. No paceFraction (no pace concept here).
- The fraction math is pure and lives next to `buildDueRows`; unit-test it (overdue -> 1f,
  half-elapsed miles -> 0.5f, no interval -> null, time-anchored path).

## Verification

- [ ] Unit tests: month-slot builder (missing month -> null), due fraction math.
- [ ] `compileDebugKotlin -Pnokey` + `testDebugUnitTest` green.
- [ ] No new queries where a DAO accessor already exists.
- [ ] On-device (map-level): recap drilldown opens; with Kevin's data (may be sparse) empty/one-
      recap states render their sentences, not blank space.

## VERIFIED BUILT 2026-08-16 - closed

Swept against HEAD during the all-effort verification. **Every one of this effort's 16 tickets was
built, wired to a production path, and unit-tested where it had a pure layer.** Each has a landing
commit. `MEMORY.md` was right that the effort shipped; **these `Status:` lines were simply never
flipped**, so the tracker counted 16 phantom open tickets and any frontier query was wrong.

Full per-ticket evidence is in the sweep record on `../map.md`.

### Exception on this ticket - Part C is a REGRESSION, not unbuilt work

Parts A (recap trends) and B (yearly wrapped) are built and wired. **Part C, the maintenance due
meter, was built in commit `7c6a5ca` and then DROPPED by the mission-control rebuild `a09aa68`.**
`DueRowView.fraction` (`ui/fleet/FleetRows.kt:111`, computed `:311`) and `ScheduleRowView.fraction`
(`:378`, `:417`) are now **orphaned data with no renderer** - `dueFraction` is still pure and still
unit-tested (`FleetRowsTest.kt:147-187`), and nothing draws it. Repo-wide `DeckMeter` callers are
only `GoalsPanel`, `BudgetSection`, `TodayScreen` and the theme preview.

Filed as part of [the silent-regression ticket](17-silent-regressions.md). Closing THIS ticket
because it was genuinely delivered; the loss belongs to whatever removed it.
