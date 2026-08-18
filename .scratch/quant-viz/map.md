---
map: quant-viz
title: "Quantitative visualization pass (\"quant-viz\")"
charted: 2026-08-13
charted-by: ""
effort: ""
tickets: 17
open: 1
status: open
tags: [map]
---
# Quantitative visualization pass ("quant-viz")

Charted 2026-08-13 (Fable, session 7 follow-on). Kevin's ask: "the data can be presented in a
better way - think visualization of quantitative data (book). the UI can be improved." Taste
delegated to the orchestrator; Kevin out of the loop until review.

## The finding this map answers

The Deck chart kit (`ui/common/DeckCharts.kt` + `DeckChartData.kt`) is complete, tested, and
Tufte-native (sparklines, gap-never-zero, exact Long-cents labels) - but it is wired into ONE
module. Body is fully wired (pane sparkline -> range-selectable drilldown chart). Everything else:

- **Money: zero charts.** Budget-vs-actual is text rows; `DeckBar.targetValue` was built for it
  and nothing calls it. No spend trend across months despite all ingredients existing.
- **Fleet: three write-only datasets.** `MonthlyRecap` (8 numeric fields, UI shows a count),
  `YearlyWrapped` (zero UI references), `OilAnalysis` (richest multi-series table in the DB,
  zero UI references).
- **Pantry:** `PantryController.totalSpendCents`/`totalSpendCentsByCurrency` computed, never
  rendered.
- **`DeckMeter` used once**; at least four target-vs-actual pairs are printed as sentences.

## Locked taste calls (do not re-open during execution)

1. **REVISED BY KEVIN 2026-08-13: "inline viz across all tabs. im not gonna read numbers. it has
   to be glancable."** Every tab's face carries inline visualization (sparklines, meters, mini
   bars) - numbers support the graphic, not the other way round. Full charts with
   `DeckRangeSelector` still live in drilldowns. This EXPLICITLY reverses the original clause
   here ("inline surfaces get at most a sparkline; Today stays chart-free") and with it
   cyberdeck-ui ticket 06 answer #4's chart-free Today - reversed by Kevin, the only authority
   over that settled ticket. Tickets 10-13.
2. **No new chart types.** Meters, bars, lines, sparklines, small multiples. No donuts, no pies,
   no stacked areas. Category mix is readable from the budget list + meters; a donut adds nothing.
3. **Ledger gap-vs-zero:** a day with no transactions INSIDE a covered statement window is a
   genuine `0` bar; a day OUTSIDE every account's covered window is a `null` gap slot. This is the
   kit's invariant read onto money via `BudgetVsActual.coverage`, and it is load-bearing - see
   ticket 01.
4. **Words stay.** Every §4 rule 5/7 disclosure sentence currently rendered stays rendered. A
   chart is ADDED next to words, never replaces them. Provisional/unverified figures keep their
   labels; charts containing them carry the same words.
5. **Money labels never touch Double/Float.** Chart geometry may be Float; every printed label
   goes through `formatCents`/`formatMoney` from Long cents (kit already enforces the seam).
6. **One definition of "spend".** The monthly trend (ticket 04) is computed by calling the SAME
   budget computation per month, never a parallel SQL aggregate that could drift from the
   exclusion rules (own-account movements, transfers).

## Tickets

| # | Ticket | Files touched (primary) | Depends on |
|---|---|---|---|
| 01 | Chart-kit groundwork: `bucketDailySumCents` + `DeckSmallMultiple` | `ui/common/DeckChartData.kt`, `DeckCharts.kt`, `DeckChartDataTest` | - |
| 02 | Budget line meters | `ui/ledger/BudgetSection.kt` | 01 |
| 03 | Category drilldown daily-spend bars | `ui/ledger/LedgerCategoryDrilldown.kt` | 01 |
| 04 | Monthly spend trend drilldown | `ledger/LedgerController.kt`, `ui/ledger/` new `SpendTrendDrilldown` | 01 |
| 05 | Fleet recap trends + maintenance due meters + wrapped surface | `ui/fleet/FleetDrilldowns.kt`, `FleetRows.kt`, `FleetScreen.kt` | 01 |
| 06 | Oil analysis small multiples | `ui/fleet/` new `OilAnalysisDrilldown`, `FleetScreen.kt` | 01 |
| 07 | Pantry spend panel + monthly bars | `ui/PantryScreen.kt`, `ui/pantry/PantryRows.kt` | 01 |
| 08 | Goal progress meters | `ui/goals/GoalsPanel.kt`, `advisor/digest/CredDigestBuilder.kt` (extract) | 01 |

Execution: 01 alone first. Then three parallel lanes on disjoint files: A = 02+03+04 (ledger),
B = 05+06 (fleet), C = 07+08 (pantry+goals). senior-dev reviews each lane's diff. qa builds and
installs on-device at the end (previews have NEVER been rendered in this repo - the on-device
check is the only real render, per L11).

## Verification (map-level)

- `compileDebugKotlin -Pnokey` + `testDebugUnitTest` green per commit; new pure functions tested.
- Every ticket's own verification section accounted for done / deferred-with-follow-up /
  impossible-and-why before the lane reports built (CLAUDE.md §8 L11).
- On-device: APK installed (sha256-verified per MEMORY.md), each new surface opened, charts render
  with Kevin's real data, gap slots visibly distinct from zero bars on at least one screen.


## VERIFICATION SWEEP 2026-08-16

Kevin: "check every ticket if built or not. repo is ahead. then close whats done."

**All 16 tickets were built, wired and tested. Every `Status: OPEN` line was stale** - the effort
shipped and nobody flipped them, so the tracker advertised 16 phantom open tickets and every
frontier query over `.scratch/` was wrong. `MEMORY.md` was right; the tracker was not. All 16 are
now closed with per-ticket evidence on the tickets themselves.

**Three carried exceptions, and none is a quant-viz failure - all three were caused by LATER work:**

- **Ticket 05 Part C and ticket 12 are silent REGRESSIONS.** The maintenance due meter shipped in
  `7c6a5ca` and was dropped by the mission-control rebuild `a09aa68`; the DRIVES miles sparkline is
  computed into state and rendered nowhere, while a code comment claims a drilldown still shows it.
  Both are filed as [two shipped visualisations vanished](issues/17-silent-regressions.md).
- **Ticket 11's SLEEP sparkline relocated** to BodyScreen when SYSTEMS SWEEP was dissolved by the
  same commit. Not lost, but ticket 11's Today requirement no longer holds and the move was never
  recorded.
- **Ticket 10 moved one tap in** - its sparkline and daily bars now render in the BUDGET drilldown
  rather than on the Money face. Nothing lost.

**The lesson worth keeping: a screen rebuild replaced composables wholesale, the pure layers
survived with their tests still green, and two features silently disappeared.** The test suite could
not have caught it - `dueFraction` still computes correctly and is still tested; nothing renders it.
