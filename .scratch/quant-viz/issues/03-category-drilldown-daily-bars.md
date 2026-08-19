---
map: quant-viz
ticket: 03
title: 03 - Category drilldown daily-spend bars
type: ""
status: resolved
status-detail: "2026-08-16, verified built in the all-effort sweep"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# 03 - Category drilldown daily-spend bars

## What

`ui/ledger/LedgerCategoryDrilldown.kt` (`CategoryDrilldownScreen`) gains a `DeckBarChart` of the
month's daily spend for the open category (including the `(uncategorised)` bucket), ABOVE the
existing transaction list. The list stays exactly as it is.

## Spec

- Data: the transactions the screen already loads for its month + category. Read the existing
  loading path first and reuse it - do NOT add a second query for the same rows.
- Bars: `bucketDailySumCents(samples, monthStartMs, monthEndMs, coveredRanges)` where
  - `samples` = the category's rows as `(txnDate, abs(amountCents))` for operating-spend rows
    (match whatever sign/filter convention the screen's list already applies - the chart must sum
    to the same figure the drilldown implies, never a different one),
  - `coveredRanges` = each `AccountCoverage(coveredFromMs..coveredToMs)` from the month's
    `BudgetVsActual.coverage` (skip accounts with null bounds). If the screen does not currently
    receive coverage, thread it in from `LedgerScreen`'s already-loaded `BudgetVsActual` - no new
    DB read.
- Map each day to `DeckBar?`: null stays null (gap slot); `DeckBar(label = dayOfMonth.toString(),
  value = cents.toFloat())`. `valueLabel = formatCents(cents)` ONLY on the single max-spend day
  (selective labels per the kit's doc); all others null. No `targetValue` - a per-day budget tick
  is noise (locked call).
- Days in the future of the CURRENT month (after today) pass null - not yet lived is a gap, not
  a zero.
- Empty case: if every slot is null or 0, render the chart anyway (baseline + gap underlines) -
  the kit handles it; do not conditionally hide.
- One sentence under the chart, `LegionType.stamp`, faint: `"daily total - days no statement
  covers are marked, not zero"` (words for the gap semantics, §4 rule 5 posture).

## Verification

- [ ] `compileDebugKotlin -Pnokey` + `testDebugUnitTest` green.
- [ ] The chart's summed cents equal the drilldown's own total for the month (add a unit test on
      the pure mapping if the mapping lives in a testable function - it should).
- [ ] No second Room query added for rows the screen already had.
- [ ] On-device (map-level): open a real category; bars match the listed transactions by eye;
      uncovered days show the gap underline.

## VERIFIED BUILT 2026-08-16 - closed

Swept against HEAD during the all-effort verification. **Every one of this effort's 16 tickets was
built, wired to a production path, and unit-tested where it had a pure layer.** Each has a landing
commit. `MEMORY.md` was right that the effort shipped; **these `Status:` lines were simply never
flipped**, so the tracker counted 16 phantom open tickets and any frontier query was wrong.

Full per-ticket evidence is in the sweep record on `../map.md`.
