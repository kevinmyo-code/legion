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

## Progress (2026-11-17, branch feat/agents-records-homes)
- Engine: four shared tables in `server/homes/` (`user_aspects`, `user_record_types`,
  `user_field_defs`, `user_records`; migration `homes 0002`), REST at `/api/records/{aspects,
  types,fields,entries}/` with `batch/`, and eight agent twins (`homes/records_tools.py`) in the
  generalist spec v4. Three names differ from the phone's (`query_aspect_records`,
  `create_aspect_record`, `update_aspect_record`, `delete_aspect_record`) because the registry's
  `read_records` / `write_record` / `delete_record` sit in the same spec; `aspect_clerk` has no
  twin (the agent's own loop replaces it).
- Only user aspects travel: a plugin-seeded aspect (fields with `ownerPluginId`) holds replica rows
  that already have typed homes on the engine.
- Phone: `backend/HomesRecordsSync.kt` (backfill, reconcile, delta write-through on
  `RecordStore.afterWrite`, pull through `RecordStore`), no Room migration: schema rows travel
  under a derived guid (kind, local id, creation instant) or the engine's, remembered.
- Eval suite `records` written, NOT recorded (the key was out of credit): the live gate is owed.
- The count gate is checked on the phone after install, from the `homes_backfill table=user_*`
  device-log lines.
