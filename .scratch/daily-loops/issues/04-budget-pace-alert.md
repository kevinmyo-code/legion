---
map: daily-loops
ticket: "04"
title: "Budget pace alert (engine watch)"
type: build
status: built
status-detail: "built 2026-11-12: watches kind budget_pace (built-in, 10% margin), watches 0002; tests/test_watches_budget_pace.py green. Owed: engine deploy with watches 0002, the phone's watch-kind list in WatchesToolbox/LiveSurface, and seeing one firing arrive on the phone"
blocked-by: []
tags: [ticket]
---

# Budget pace alert

A standing watch, `budget_pace`, reading `finance.budgets` (the Money tab's own figures). It fires
once per category per month when the month-end estimate is at least `over_percent` (default 10)
over the cap, or when the category is already over. Before day 10 there is no estimate, so only
"already over" is said. The estimate is said as one; a figure built on unverified rows says so.
Seeded as a built-in, so it is paused or removed like any watch (compulsion test clause d).

## Verification

- `tests/test_watches_budget_pace.py` (6) plus `test_watches.py` and `test_finance_budgets.py`: green.
- Owed: deploy, then one real firing on the phone (said or notified, with the estimate wording).
