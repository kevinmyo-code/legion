---
map: engine-brain
ticket: "03"
title: "Engine homes for Room-only data before the phone loop goes"
type: build
status: open
size: L
blockers: ["00"]
blocked-by: ["[[00-rulings]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Engine homes for Room-only data before the phone loop goes

Stage 2. Nothing may be lost on the way to a thin client (ADR 0095).

## Build
Give each of these an engine table and REST endpoint, with phone write-through and a one-time backfill of existing Room rows:
- goals and advisor advice (an engine `goals` table already exists in `server/legacy/models/body.py`: check how it syncs first);
- voice-logged pending transactions;
- user-aspect records: aspect, record type, field def and record as fixed metadata tables, never runtime DDL;
- proactive raises (what `why_did_you_say_that` reads);
- the service-record push that the Django transport refuses today.

## Verification (gates)
- [ ] Backfill counts equal the phone's Room counts (logged as counts only).
- [ ] New tables are in the tenancy leak and visibility tests.
- [ ] Phone suite chunks green; migrations additive only.

## Progress (2026-11-17, branch feat/agents-engine-homes)
Split: this ticket ships goals, advisor advice, voice-logged pending transactions, proactive raises
and the service-record push. User-aspect records moved to ticket 15.
- Engine: app `server/homes/` (new tables `advisor_advice`, `pending_transactions`,
  `proactive_raises`; REST doors for those and the legacy `goals`; `POST <table>/batch/` for the
  backfill), eight agent tools (`homes/tools.py`) in the generalist spec, eval suite `homes`.
- Phone: `backend/HomesSync.kt` (backfill once per table, reconcile, write-through, pull of what the
  engine wrote) and the Django service-record push by `origin_guid`.
- The count gate is checked on the phone after install, from the `homes_backfill` device-log lines.

