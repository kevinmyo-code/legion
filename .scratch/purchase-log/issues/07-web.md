---
map: purchase-log
ticket: "07"
title: "Web: the log screen, log-it form, last-bought on list items"
type: build
status: built
status-detail: "Web built, Vitest 535 passed and Playwright 19 passed; owes a run on Mia's iPhone and the deploy"
blockers: ["05", "06"]
blocked-by: ["[[05-where-it-lives-on-screen]]", "[[06-server]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Web: the log screen, log-it form, last-bought on list items

## Build

Per 05 and 06, in `server/frontend/`: the family-view and workbench surfaces, the hand form, last
bought on Groceries items, private entries hidden from the other member. Vitest + Playwright green.

## Built (2026-10-04, feat/purchase-web)

- `server/frontend/src/`: `api/purchases.ts` (reads and writes over the generated client),
  `lib/purchases.ts` (price in whole cents, wording, which entry a Groceries line names),
  `components/purchases/` (form, entry rows, Home pill), `screens/bought.tsx` (phone search and
  desk table with the form beside it), `screens/bought-log.tsx`, routes `/bought` and `/bought/log`.
- Variant C: Home gets a "When did we last buy...?" pill; the search answers with the exact entry
  ("Shampoo, bought Sep 20 by Mia"), then the other matches with source chips; no match is "No record
  of buying X.". Search and the recent list read `GET /api/purchases/?q=` (the same matcher as
  `last-bought`), so the other matches are all there, not one per distinct text.
- The untick sends `?today=<local epoch day>`. Groceries lines show "last bought <date> - <who>" from
  one read of the log; other lists show nothing new (no "last ticked" label exists on the web).
- Unreachable log says "Can't reach the bought log right now. Nothing was logged." and keeps the typed
  fields; a read that fails is never drawn as an empty log.
- Edit and delete show on entries the member logged, and on ones nobody is recorded as having logged.
