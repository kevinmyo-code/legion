---
map: legion-shape
ticket: 06
title: "What is the ledger's budget-versus-actual, exactly?"
type: grilling
status: resolved
status-detail: "2026-08-07, Kevin"
blockers: ["05", "07"]
blocked-by: ["[[05-target-log-gap-vocabulary]]", "[[07-categorisation]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# What is the ledger's budget-versus-actual, exactly?

## Question

Ticket 04 replaced the P&L with budget-versus-actual. Specify it.

1. **Setting a budget.** Per category per month. How is it set - by voice, on a screen, or both?
   Does it copy forward to next month by default?
2. **What "remaining" means mid-month.** Kevin: *"as i spend, i want to see how much i can still
   use per category"*. Straight subtraction, or pace-aware (you are 12 days in and 60% spent)?
3. **Uncategorised spend.** A transaction with no category yet still left the account. Does it count
   against a budget, sit in an "uncategorised" bucket, or hold the whole figure back? A wrong answer
   here makes every category look under-budget.
4. **Provisional rows.** Card CSV rows are `UNRECONCILED` (ticket 02). They are real spending you
   have not been billed for yet. In the remaining figure or not - and how labelled?
5. **Coverage.** `coversMonthWithoutGaps` already reports when a month is not fully covered by
   imported statements. What does "£180 left on groceries" mean when a statement is missing? State
   what the screen says.
6. **Reuse.** Name exactly what is kept from `.scratch/ledger-pnl/`: `analyzeTransfers`,
   `coversMonthWithoutGaps`, `LedgerEntity`, the UTC month boundaries. Name what is deleted.

---

## Resolution (2026-08-07, Kevin - D9-D13)

**9. Set by voice AND screen.** Voice is the point of the app; a screen is how you see all categories
at once.

**10. Remaining is plain subtraction.** Budget minus spent. No pace-awareness, no burn-rate, no
projection. Simple first.

**11. Uncategorised spend gets its OWN bucket, shown loudly.** Never spread across categories, never
hidden, never silently excluded. If it were hidden, every category would look healthy while the total
lied - the same shape of failure as CLAUDE.md section 4 rule 6.

**12. Provisional rows count, and are marked.** `UNRECONCILED` card rows are money that left; the
figure includes them and says so. This is ticket 02's rule and matches what `AccountBalanceRow`
already does.

**13. Missing coverage is stated in words, next to the number.** "$180 left - January's card
statement isn't imported." `coversMonthWithoutGaps` already computes this; it must reach the user's
eye, not just the log.

**Reused unchanged from `.scratch/ledger-pnl/`:** `analyzeTransfers` (transfer matching - a budget
double-counts a card payment exactly as badly as a P&L did), `coversMonthWithoutGaps`,
`LedgerEntity`, UTC month boundaries, `Long` cents. **Deleted:** `ProfitAndLossSection` and the
`ProfitAndLoss` income/expense/net shape.
