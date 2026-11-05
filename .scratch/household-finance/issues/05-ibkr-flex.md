---
map: household-finance
ticket: "05"
title: "IBKR Flex connector"
type: build
status: open
status-detail: "charted 2026-11-05"
blockers: ["01", "03"]
blocked-by: ["[[01-accounts-and-snapshots]]", "[[03-fx-rates]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# IBKR Flex connector

## What
- Store the Flex Web Service token and query id encrypted with the vault key (`SourceCredential`, like Plaid).
- Daily job: SendRequest, then GetStatement. Parse positions (symbol, quantity, mark price, currency) and cash. Write a `BalanceSnapshot` per day with the report id as evidence. Holdings stay detail rows.
- Failure says in words what was not updated; the last good snapshot keeps its date.
## Verification
- pytest against a recorded synthetic Flex XML. No network in tests.
