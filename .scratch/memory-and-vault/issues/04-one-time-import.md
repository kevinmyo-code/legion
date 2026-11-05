---
map: memory-and-vault
ticket: "04"
title: "One-time import of the phone's memories"
type: build
status: open
status-detail: ""
blockers: ["01"]
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# One-time import of the phone's memories

## What

- `POST /api/member-memories/import` `{rows: [{origin_guid, store, text, category, source,
  importance, logged_at, last_accessed_at}]}`, up to 500 rows a call. Idempotent by store and
  `origin_guid`. Every row is private to the caller.
- Mapping: `car_anchored` and `stated` rows are durable facts; `consolidated` and `reflection`
  rows are episodic events; flat `memories` rows (what `remember` wrote) are durable facts with
  importance 5.
- **Found while charting:** both phone tables already replicate to the engine's legacy
  `companion_memories` and `memories`. `manage.py import_legacy_memories --member <email>` does
  the same import from those tables with no phone involved. Kevin may prefer it; the endpoint
  stays for a phone whose rows never synced.
- Rows the vault filter refuses are skipped and counted, never stored.

## Verification

- pytest: a second identical call writes nothing, the mapping, the command, recruiter refused.
