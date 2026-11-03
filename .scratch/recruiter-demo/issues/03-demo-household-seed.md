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
- The bad receipt stays in the seed and its gate test, but nothing a recruiter sees mentions rejected
  or quarantined receipts: no chip, no greeting, no copy (Kevin, 2026-11-02: *"we dont do receipt
  ingestion anymore"*).

## Verify

- Test: seed is idempotent; the bad receipt lands quarantined with its anchors stored (section 4 rule 8).
