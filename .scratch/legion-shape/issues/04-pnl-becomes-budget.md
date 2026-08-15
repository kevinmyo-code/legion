# What happens to the P&L?

Type: grilling
Status: resolved (2026-08-06, Kevin)
Blocked by: 03

## Question

A US-entity monthly P&L was specified and built on 2026-08-06
(`.scratch/ledger-pnl/issues/01-entity-profit-and-loss.md`, 293 tests green, uncommitted). Hours
later Kevin said *"the ledger is just for a budget"*. A P&L answers "what came in and went out last
month". A budget answers "how much of groceries is left". These are different questions.

## Resolution

**Replaced by budget-versus-actual.** The P&L top layer does not ship as the ledger's answer.

**What survives, and it is most of the work:**
- `LedgerTransfers.analyzeTransfers` - matched-pair transfer detection plus keyword fallback. A
  budget double-counts card payments exactly as badly as a P&L does. **Needed either way.**
- `coversMonthWithoutGaps` and the coverage reporting - a budget computed over a month with an
  unimported statement is wrong in the same silent way. **Needed either way.**
- `LedgerEntity` - US/SG by currency.
- The month boundaries, the UTC convention, the `Long` cents discipline.

**What changes:** `ProfitAndLoss`'s income/expense/net becomes budget/spent/remaining **per
category**, which means it now depends on categorisation (ticket 07) that does not exist.

**Cost of the change: near zero, because it was never committed.** This is the argument for the
whole map: the P&L was built correctly, to spec, with 16 tests, against a spec written the same day
from a decision that had not been made yet. Nothing was wrong with the execution.

## Follow-on

Do not commit the P&L UI layer as-is. The pure logic underneath should be kept and committed; the
`ProfitAndLossSection` composable and the `ProfitAndLoss` shape are superseded by ticket 06.
