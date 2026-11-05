---
map: household-finance
ticket: "07"
title: "Net worth, cash flow, runway and the income plan"
type: build
status: open
status-detail: "charted 2026-11-05"
blockers: ["01", "02", "03"]
blocked-by: ["[[01-accounts-and-snapshots]]", "[[02-household-internal-money]]", "[[03-fx-rates]]"]
open-blockers: 3
ready: false
tags: [ticket]
---
# Net worth, cash flow, runway and the income plan

## What
- `IncomePlan` rows (member, label, amount, currency, cadence, start, end, `confidence`: confirmed | expected | fallback), loaded from the private file.
- `GET /api/finance/networth?ccy=USD|SGD`: per layer and total, each figure with as_of, source and estimate flag. The SGD view uses today's rate.
- `GET /api/finance/cashflow?month=`: household income, spend (netted per ticket 02), mortgage, net. USD headline.
- `GET /api/finance/runway`: projects liquid month by month from the income plan and the average spend (last 3 months, netted), and returns:
  - the month liquid crosses the floor
  - the cross-border transfer needed and by when, to stay above the floor through the plan's gap months
  - the US top-up check (Kevin's checking minus card owed vs next 30 days of US bills + statement balance)
- Engine tools for Alfred: `net_worth`, `cash_flow`, `runway`. Read-only, pull-based.
## Verification
- pytest with a synthetic household: a gap month triggers a transfer recommendation, the floor date, and the conversion rate shown.
