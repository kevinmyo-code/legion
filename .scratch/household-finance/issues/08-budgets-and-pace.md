---
map: household-finance
ticket: "08"
title: "Budgets and pace"
type: build
status: open
status-detail: "charted 2026-11-05"
blockers: ["02"]
blocked-by: ["[[02-household-internal-money]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Budgets and pace

## What
- Use the existing `BudgetTarget` rows (USD), loaded from the private file.
- `GET /api/finance/budgets?month=`: per category spent, cap, percent, and pace projection (spent / days elapsed x days in month, only from day 10). Pending rows are counted and labelled pending.
- A subscriptions list: recurring merchants detected from 3+ months of history (amount band, cadence), including new ones this month.
- Engine tool `budgets` for Alfred.
## Verification
- pytest: pace maths, the day-10 gate, pending labelled, a new subscription detected.
