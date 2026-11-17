---
map: engine-brain
ticket: "15"
title: "Engine homes for user-aspect records (split from 03)"
type: build
status: open
size: M
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Engine homes for user-aspect records (split from 03)

Stage 2b. Ticket 03 shipped goals, advisor advice, voice-logged pending transactions, proactive
raises and the service-record push; this is the part of it left over, cut so the first half could
ship whole.

## Build
- Aspect, RecordType, FieldDef and EngineRecord as fixed metadata tables on the engine, never
  runtime DDL (CLAUDE.md section 1); tenancy and `visible()` per ADR 0045 / 0052, in the leak and
  visibility tests.
- REST doors on the homes shape (`server/homes/views.py`: synced routes plus `POST <table>/batch/`).
- Engine twins of the phone's records tools (the 8 CRUD and aspect tools) for the generalist spec,
  reads and writes classified, with eval cases on invented data.
- The phone's write-through and a one-time backfill through `HomesSync` (idempotent, resumable,
  counts logged as `homes_backfill`), Room kept as the replica.

## Verification (gates)
- [ ] Backfill counts equal the phone's Room counts (logged as counts only).
- [ ] New tables are in the tenancy leak and visibility tests.
- [ ] Phone suite chunks green; migrations additive only.
