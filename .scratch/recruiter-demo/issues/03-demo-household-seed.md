---
map: recruiter-demo
ticket: "03"
title: "Demo household seed: fake bank, car, receipts, one failing receipt, nightly reset"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Demo household seed: fake bank, car, receipts, one failing receipt, nightly reset

## What

`manage.py seed_demo_household` builds household "Demo" with FAKE data only:
- Ledger: about 3 months of bank rows, provenance mixed so verified and estimate labels both show.
- Fleet: one car with service history and a due item.
- Pantry: receipts, including ONE whose lines do not sum to its printed total, sent through the real
  gate (`ingest/gate.py`) so the engine itself quarantines it, not the seed.
- Notes, dates, places: a handful each.

Nightly: wipe and reseed.

## Rules

- No row copied from Kevin's household. Names, merchants, amounts all invented.
- Show what the engine ACTUALLY does with the bad receipt; demo copy follows the behaviour.

## Verify

- Test: seed is idempotent; the bad receipt lands quarantined with its anchors stored (section 4 rule 8).
