---
map: memory-and-vault
ticket: "10"
title: "Phone: upload once, retire the local consolidator"
type: build
status: open
status-detail: ""
blockers: ["03", "04", "09"]
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Phone: upload once, retire the local consolidator

## What

- Once per install: upload `companion_memories` and `memories` rows to
  `POST /api/member-memories/import` in batches of 500; a stored flag stops a second run. (Or
  Kevin runs `manage.py import_legacy_memories` instead: ticket 04.)
- Stop `MemoryConsolidator` and `ReflectionEngine` writing locally once the engine job runs
  (ticket 03), so nothing is distilled twice. Episodic turns stop being captured.
- The legacy memory sync (`MemorySync`, `MemoryOutbox`) stops writing new rows; the tables stay.

## Verification

- JVM: the upload runs once, a failed batch retries, the consolidator is off.
