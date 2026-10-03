---
map: web-revamp
ticket: 16
title: Pantry and body workbench
type: build
status: open
status-detail: ""
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

- [ ] vitest per screen: smoke at both surfaces (bigger-screen card at family width), estimate and
      unaccounted wording, one CRUD round-trip per table, empty-state sentence.
- [ ] Shots in `research/shots/16/`.
