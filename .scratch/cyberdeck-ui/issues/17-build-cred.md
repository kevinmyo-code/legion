---
map: cyberdeck-ui
ticket: 17
title: "Build: CRED rebuild"
type: task
status: closed
status-detail: "2026-08-16, superseded by .scratch/mission-control/"
blockers: ["13", "14"]
blocked-by: ["[[13-build-shell]]", "[[14-build-chart-kit]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Build: CRED rebuild

## Question

Rebuild LedgerScreen per ticket 08: BURN/BALANCES/FLOW panels + drilldowns (per-category bars,
burn-down line, balance trajectory, monthly trends - new aggregate DAO queries, month boundaries
per the existing UTC document-date convention for statement data); OPS checklist row collapsing
scan/mapping/pending/guesses with tap-through (red on quarantine); STREAM inline with existing
drilldown/recategorize flows re-skinned. §4 rule 7 wording on BURN/BALANCES when provisional
rows are inside. Dispatch analyst on the new aggregate queries (money math).

## Closed 2026-08-16 - SUPERSEDED, not built to this spec

Verified against the tree during the all-effort sweep. **A CRED rebuild shipped, to a different
spec.** `cyberdeck-ui/map.md:6-9` marks build tickets 12-20 superseded by `.scratch/mission-control/`,
and `LedgerScreen.kt:894-899` names its own shape "mission-control ticket 16/ticket 12's inventory".
This ticket's body is a stale spec; closing it as superseded rather than pretending it was met.

**Shipped, though not as written:** per-category bars (`categorySpendBars`, `LedgerScreen.kt:985-1000`),
monthly trends (`SpendTrendDrilldown.kt`, self-attributed to quant-viz ticket 04), the STREAM
equivalent as RECENT ACTIVITY (`:975-981`) with the recategorise path intact, and rule 7 wording on
every pane that exists (`:1007`, `:1066`, `:1116`). `LedgerScreen` is routed (`MainActivity.kt:495`);
no orphans in the surface.

**Named in this ticket and NOT built anywhere** - recorded so closing does not lose it:
- **BURN and FLOW panels.** `ui/common/DeckChartData.kt:346` still reads "whichever screen ticket
  ends up wiring a real BURN/FLOW panel to it" - the primitive waits, the panel never came.
- **Burn-down line** and **balance trajectory**: zero hits anywhere in `app/src/main`.
- **New aggregate DAO queries.** `LedgerController.monthlySpendTrend` (`:464-478`) still loops months
  in Kotlin calling `budgetVsActual`; `LedgerTransactionDao` has no month or category GROUP BY
  aggregate. UTC boundaries ARE used (`ZoneOffset.UTC`, `:466`).
- **The collapsed OPS checklist row.** The information survives as separate drilldowns
  (`CategorizeDrilldownScreen`, `QuarantineDrilldownScreen`); the single collapsed row does not.
