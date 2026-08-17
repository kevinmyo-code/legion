# 10 - Money tab inline viz (glanceable face)

Status: resolved (2026-08-16, verified built in the all-effort sweep)
09 landing first - same files (`ui/LedgerScreen.kt`, `ui/ledger/`), do not run concurrently.

## What

The Money tab face currently shows zero graphics until a target exists. Two always-on inline
pieces, both from data the screen (or controller) already computes:

1. **Spend trend sparkline in the US BUDGET header area.** Directly under the `< MONTH >`
   navigator row: `DeckSparkline` of the last up-to-12 months' total spend from
   `LedgerController.monthlySpendTrend` (already built, ticket 04). Trend-list holes map to
   `null` gaps exactly as `monthlySpendBars` already does - reuse that mapping's month-hole
   logic, points = totalCents.toFloat(). One faint stamp line under it:
   `"spend, last <n> months - tap month for detail"`. The sparkline itself is also tappable,
   opening the same SpendTrendDrilldown the month label opens.
2. **Current-month daily-spend mini bars.** A ~54dp-height inline `DeckBarChart`-style strip is
   NOT what the kit's 180dp DeckBarChart gives; instead reuse `DeckSparkline`? No - bars read
   better for daily spend. Add nothing to ui/common: render the existing `DeckBarChart` but
   height is fixed at 180dp there... ACCEPTABLE RESOLUTION: place the full `DeckBarChart` of the
   CURRENT month's daily spend (all categories combined, same coverage gap rule as ticket 03,
   built from `bucketDailySumCents` over the month's operating-expense rows the screen already
   loads for `budgetVsActual`) directly on the tab face, between the sparkline and the budget
   lines. 180dp on the face is fine - this is the tab's hero graphic, per Kevin's ruling.
   valueLabel on the max day only. Caption: `"daily spend - days no statement covers are
   marked, not zero"`.
3. Loading/empty behaviour: while `budget == null` render nothing new (existing "Loading...").
   A month with zero rows renders the empty-baseline chart, never hides it.

## Verification

- [ ] Any new pure mapping unit-tested (daily all-category sums = sum of per-category ticket 03
      series for the same month; exact Long).
- [ ] `compileDebugKotlin -Pnokey` + `testDebugUnitTest` green.
- [ ] No change to ui/common/.
- [ ] On-device: Money tab face shows sparkline + daily bars at first glance with real data.

## VERIFIED BUILT 2026-08-16 - closed

Swept against HEAD during the all-effort verification. **Every one of this effort's 16 tickets was
built, wired to a production path, and unit-tested where it had a pure layer.** Each has a landing
commit. `MEMORY.md` was right that the effort shipped; **these `Status:` lines were simply never
flipped**, so the tracker counted 16 phantom open tickets and any frontier query was wrong.

Full per-ticket evidence is in the sweep record on `../map.md`.

### Note on this ticket - built, then moved one tap in

Everything this ticket built still renders in production, but **not on the Money tab face**.
`LedgerScreen.kt:600` records that the block moved into `BudgetDrilldownScreen` during
mission-control ticket 16; the Money face now shows `SpendPane`'s category bar hero
(`LedgerScreen.kt:1013-1060`). Nothing was lost; the placement changed.
