---
map: web-revamp
ticket: 12
title: Money workbench
type: build
status: built
status-detail: "/money (five tabs, virtualised, optimistic category) and Home's Spent this month and Needs a decision panels; 42 vitest cases and light/dark shots in research/shots/12. Owed on live: categorise one row on the web, see it on the phone."
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

- [x] vitest: partial fetch shows "Still loading", never a total; set and clear category round-trip
      and roll back on failure; filters (account, month, needs a category, unverified); the word
      "unverified" in the status column and beside any total containing one; Files states in words;
      the not-spending switch PUTs `excluded_from_spend`.
- [x] Shots at 1440x900 light and dark in `research/shots/12/` (and the 390x844 bigger-screen card).
- [ ] Owed on live (Kevin): categorise one row on the web, see it on the phone after its mirror.

## Resolution (2026-10-03)

Built in `server/frontend/`: `screens/money*.tsx`, `api/ledger.ts`, `lib/ledger.ts`,
`components/workbench/{category-combobox,spent-by-account,home-money}.tsx`, route `/money`
(`?tab=` and `?need=true` are what Home links to). Tests: `routes/-money.test.tsx`.

Not built, on purpose, each with its reason:

- **Weekly bars on Home's Spent this month.** `GET /api/ledger/spend` returns per-account and
  per-category totals, no weekly split. Drawing bars from transactions would be a client-side spend
  computation, a third definition of "spend" (spec D5). Needs a server field first.
- **Removing a budget target.** Targets are rows with an effective month; deleting one rewrites
  history for earlier months. Setting a target to a number is built; clearing one is not.
- **A category rename.** Transactions, rules and targets name a category by its string, so the name is
  read-only once it exists (add a new category instead).
- **"Needs a decision" counts only `QUARANTINED` files.** Unreadable and duplicate files stay on the
  Files tab; a count that can never reach zero is a nag, not a decision.

Owed on live (Kevin): categorise one row on the web, see it on the phone after its mirror.
