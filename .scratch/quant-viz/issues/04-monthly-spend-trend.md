# 04 - Monthly spend trend drilldown

Status: resolved (2026-08-16, verified built in the all-effort sweep)

## What

Month-over-month total spend, as a bar chart in its own drilldown, opened by tapping the month
label in `BudgetSection`'s header row (the `< AUGUST 2026 >` line). Same internal-Compose-state
pattern `CategoryDrilldownScreen` uses - no nav-graph routes.

## Spec

### Data (ledger layer, `ledger/LedgerController.kt` or `LedgerBudget.kt` - follow where
`BudgetVsActual` is built)

```kotlin
data class MonthSpend(val month: YearMonth, val totalCents: Long, val isComplete: Boolean,
                      val hasProvisionalRows: Boolean)

suspend fun monthlySpendTrend(context: Context, entity: LedgerEntity, maxMonths: Int = 24): List<MonthSpend>
```

- Iterate months from `max(oldest txnDate month, now - maxMonths + 1)` through the current month,
  calling the SAME function that builds `BudgetVsActual` for the screen (locked call 6: one
  definition of spend, exclusions included).
- `totalCents = lines.sumOf { it.gap.actual } + uncategorized.spentCents`.
- `isComplete = budget.isComplete`; `hasProvisionalRows` = any line or the uncategorized bucket
  has provisional rows.
- Months with zero rows AND no coverage: skip entirely at the edges, but interior empty months
  render as `null` slots (a gap in the record, not a $0 month). Interior month with coverage and
  no spend: a genuine 0 bar.

### UI (`ui/ledger/SpendTrendDrilldown.kt`, new file)

- Header: `SectionHeader("SPEND BY MONTH")` + entity name, back affordance matching
  `CategoryDrilldownScreen`'s.
- `DeckBarChart`: one bar per month; `label` = "JAN".."DEC"; `valueLabel = formatCents` on the
  LATEST month only. No targetValue (no monthly total budget exists; do not derive one by summing
  category targets - that would render a target Kevin never set).
- Below the chart, a plain list: one `DeckRow` per month, newest first, value =
  `formatMoney(totalCents, entity.currency)`; months with `!isComplete` append the word
  `"(incomplete)"` INSIDE the value string and the row's own line
  `"not every account covered - total may be low"` in `sem.quarantined`, matching
  `coverageSentence`'s posture. `hasProvisionalRows` months append `"includes pending rows"`
  faint. Words, not color alone (§4 rule 5).
- Loading state: `"Loading..."` stamp, same as BudgetSection.

## Verification

- [ ] Unit test on the trend builder with fixture months: interior no-coverage month is null-slot;
      covered-but-empty month is 0; totals are exact Long sums; incomplete flag propagates.
- [ ] `compileDebugKotlin -Pnokey` + `testDebugUnitTest` green.
- [ ] The current month's bar equals BudgetSection's own displayed total for that month.
- [ ] On-device (map-level): tap month label, trend opens, figures match month-by-month
      navigation via the existing arrows.

## VERIFIED BUILT 2026-08-16 - closed

Swept against HEAD during the all-effort verification. **Every one of this effort's 16 tickets was
built, wired to a production path, and unit-tested where it had a pure layer.** Each has a landing
commit. `MEMORY.md` was right that the effort shipped; **these `Status:` lines were simply never
flipped**, so the tracker counted 16 phantom open tickets and any frontier query was wrong.

Full per-ticket evidence is in the sweep record on `../map.md`.
