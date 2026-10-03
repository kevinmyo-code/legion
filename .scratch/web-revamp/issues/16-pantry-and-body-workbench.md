---
map: web-revamp
ticket: 16
title: Pantry and body workbench
type: build
status: built
status-detail: "Built on feat/web-aspects: /pantry and /body, 53 tests in 4 files, shots in research/shots/16. Needs Kevin on live data."
blockers: ["03"]
blocked-by: ["[[03-the-shell-split-by-viewport]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Pantry and body workbench

Charted 2026-10-03 with Kevin. Map: `.scratch/web-revamp/map.md`.

See `.scratch/web-revamp/spec.md` D1 rail; web-and-households 06 trust rules carried over verbatim.

## Build

- `/pantry`: grocery staples CRUD; receipts with line items read only, macros labelled "estimate",
  `unaccounted_cents` shown as "unaccounted", provenance in words ("unverified" for UNRECONCILED).
- `/body`: bodyweight, sleep, meals vs targets, workout plans and set logs; CRUD on the synced
  tables. Charts via shadcn charts (Recharts): sparklines, bars, meters only; no donuts or pies;
  every empty state in words, never an empty axis.

## Verification

- [x] vitest per screen: smoke at both surfaces (bigger-screen card at family width), estimate and
      unaccounted wording, one CRUD round-trip per table, empty-state sentence. Done: every writable
      table (staples, weight, sleep, sleep target, meal, meal target, set log, plan, plan item) has a
      round-trip through the fake engine; receipts and line items are GET only on the engine, so they
      have the read, the wording and the 405-by-design instead.
- [x] Shots in `research/shots/16/`.

## Resolution (2026-10-03, branch `feat/web-aspects`)

Built: `/pantry` (staples CRUD, receipts and line items read only) and `/body` (Weight, Sleep, Meals,
Workouts tabs; eight tables, all CRUD). Files: `src/screens/pantry.tsx`, `body*.tsx`, shared
`src/components/workbench/*`, `src/api/{synced,aspects,refusal}.ts`, `src/lib/{figures,body}.ts`.

Decisions taken where the spec was silent, each for Kevin to overrule:

- **"Times ticked", never "bought".** `grocery_staples.times_bought` is, on the phone, the number of
  completed trips it was ticked on (`GroceryStaple.kt`), so ADR 0049 binds the label.
- **A typed body entry is `REPORTED`**, a typed service entry is `ASSERTED`, a saved maintenance
  interval is `CONFIRMED`. Editing a `PROVEN` or `OBSERVED` row says it becomes reported or asserted.
- **A seeded maintenance interval carries the word "estimate"** (the phone's `isGuessTag`).
- **Meal macros are "estimate" wherever logged, including when typed by hand**, because the engine's
  own column help says so.
- A new staple's key is the spelling lowercased. The phone also strips a trailing "s" (`normalizeGroceryName`);
  that is not copied, so "bananas" typed here would not match the phone's "banana".
