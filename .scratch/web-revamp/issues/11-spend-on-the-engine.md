---
map: web-revamp
ticket: 11
title: "Spend on the engine: one figure, computed once"
type: build
status: built
status-detail: "GET /api/ledger/spend ported from LedgerBudget/LedgerTransfers/BudgetMonth/MoneyMonth; 60 phone tests transcribed and green. Owed on live: Mia's card equals the phone to the cent."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Spend on the engine: one figure, computed once

Charted 2026-10-03 with Kevin. Map: `.scratch/web-revamp/map.md`.

See `.scratch/web-revamp/spec.md` D5. Overrides web-and-households 11's RLS blocker for this one endpoint; flagged in the map.

## Build (server)

- `server/api/spend.py`: port of `LedgerBudget.operatingExpenses`, `LedgerTransfers.analyzeTransfers`
  and `BudgetMonth`. Route `GET /api/ledger/spend?month=&tz=`. Keys on `account_last4`.
- `server/tests/test_spend_parity.py`: fixtures transcribed from the phone's unit tests for those
  three classes (name each Kotlin test in the fixture).
- OpenAPI + `gen:api`.

## Verification

- [x] pytest parity: every transcribed case equals the phone's cents.
- [x] pytest: unverified true iff a contributing row is UNRECONCILED; nickname drift on one last4
      yields one account; Housing on the 29th moves to next month; Transfers excluded and disclosed.
- [x] Leak test row for the new route.
