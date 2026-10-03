---
map: web-revamp
ticket: 12
title: Money workbench
type: build
status: open
status-detail: ""
blockers: ["03", "04", "11"]
blocked-by: ["[[03-the-shell-split-by-viewport]]", "[[04-live-refresh-while-visible]]", "[[11-spend-on-the-engine]]"]
open-blockers: 3
ready: false
tags: [ticket]
---
# Money workbench

Charted 2026-10-03 with Kevin. Map: `.scratch/web-revamp/map.md`.

See `.scratch/web-revamp/spec.md` D9.

## Build

- `/money` with tabs Transactions, Budgets, Categories, Rules, Files. `@tanstack/react-virtual`.
- `useLedgerTransactions` pages to `next: null` and reports progress; never totals a partial fetch.
- Category combobox: PUT/DELETE `transaction_categories`, optimistic with a rollback sentence.
- Workbench Home's money panel and "Needs a decision" counts read the same hooks.
- Rail item "Money" flips to built.

## Verification

- [ ] vitest: partial fetch shows "Still loading", never a total; set and clear category round-trip
      and roll back on failure; filters (account, month, needs a category, unverified); the word
      "unverified" in the status column and beside any total containing one; Files states in words;
      the not-spending switch PUTs `excluded_from_spend`.
- [ ] Shots at 1440x900 light and dark in `research/shots/12/`.
- [ ] Owed on live (Kevin): categorise one row on the web, see it on the phone after its mirror.
