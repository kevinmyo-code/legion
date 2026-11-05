---
map: household-finance
ticket: "01"
title: "Household accounts and balance snapshots"
type: build
status: open
status-detail: "charted 2026-11-05"
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Household accounts and balance snapshots

## What
- New engine app `server/finance/`. Tables are household-scoped and listed in `TENANT_TABLES`, with tenancy tests.
- `FinanceAccount`:
  - key, label, owner member (nullable = joint), institution, country, currency
  - `kind`: depository | credit | brokerage | bond | cpf | property | loan | vehicle
  - `layer`: liquid | investments | cpf | property | other
  - `source`: plaid | statement | ibkr_flex | computed | manual
  - `last4` (nullable), `plaid_account_id` (nullable), `statement_match` (how a dropped statement is recognised as this account)
  - `active`
- `BalanceSnapshot`:
  - account, as_of (date), amount_cents (signed: debt negative), currency, source
  - `evidence`: statement id / plaid item / ibkr report id / computation name
  - `is_estimate`
  - unique (account, as_of, source)
- Plaid-linked accounts get a daily snapshot from `PlaidItem.accounts` (current balance; credit = negative). Pending stays visible as a separate field.
- Read API: `GET /api/finance/accounts`, `GET /api/finance/snapshots?account=&from=&to=` (members).
## Verification
- pytest: tenancy leak, Plaid snapshot sign, unique constraint, estimate flag carried.
