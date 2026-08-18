---
map: quant-viz
ticket: 07
title: 07 - Pantry spend panel + monthly bars
type: ""
status: resolved
status-detail: "2026-08-16, verified built in the all-effort sweep"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# 07 - Pantry spend panel + monthly bars

## What

`PantryController.totalSpendCents` / `totalSpendCentsByCurrency` (`pantry/PantryController.kt:59-64`)
are computed and never rendered; `PantryScreen.kt` shows a receipt count. Add a SPEND panel: totals
per currency plus a monthly bar chart of receipt totals.

## Spec

- Panel placement: above the receipt list on `ui/PantryScreen.kt`, `SectionHeader("GROCERY SPEND")`.
- Totals: one `DeckRow` per currency from `totalSpendCentsByCurrency`, value =
  `formatMoney(cents, currency)`. NEVER a combined all-currency figure (the DAO doc's own rule).
- Monthly bars: group the receipts the screen already loads (`recentReceiptsWithItems` may be
  limited to 10 - if so, add a lightweight DAO/controller read of `(purchaseDate, totalCents,
  currency)` for ALL receipts; do NOT lift the receipt-list limit itself).
  - Chart ONLY the currency with the most receipts; other currencies stay text-total rows. One
    faint sentence says so: `"chart shows CUR only - other currencies in the totals above"`.
  - One bar per calendar month from first receipt month to current month. A month with no
    receipts is a `null` GAP slot - groceries were bought (people eat); the record just has no
    receipt, which is exactly the gap-not-zero case.
  - `valueLabel = formatCents` on the latest month only. No targetValue.
- Receipt totals are gate-passed facts (reconciled against the printed total), so no estimate
  labelling here. Do NOT chart macros in this ticket - macros are LLM estimates and stay in
  their labelled text rows.
- Empty state (no receipts): the panel renders header + `"no receipts ingested"` - never hidden.

## Verification

- [ ] Pure month-grouping function unit-tested: gap month -> null, currency selection by receipt
      count, Long-exact sums.
- [ ] `compileDebugKotlin -Pnokey` + `testDebugUnitTest` green.
- [ ] No combined-currency sum anywhere in the diff.
- [ ] On-device (map-level): totals match the sum of listed receipts for the visible currency.

## VERIFIED BUILT 2026-08-16 - closed

Swept against HEAD during the all-effort verification. **Every one of this effort's 16 tickets was
built, wired to a production path, and unit-tested where it had a pure layer.** Each has a landing
commit. `MEMORY.md` was right that the effort shipped; **these `Status:` lines were simply never
flipped**, so the tracker counted 16 phantom open tickets and any frontier query was wrong.

Full per-ticket evidence is in the sweep record on `../map.md`.
