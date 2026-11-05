---
map: memory-and-vault
ticket: "09"
title: "Phone: memory tools move to the engine"
type: build
status: built
status-detail: "phone built 2026-11-05 on feat/memory-vault-phone, JVM green; one `memory` tool (recall/remember/forget/list) to fit the payload ceiling; owed: engine deploy, A25 run (ticket 14)"
blockers: ["02"]
blocked-by: ["[[02-memory-tools]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Phone: memory tools move to the engine

## What

- `recall_memory` calls `POST /api/member-memories/recall` and speaks only what it returns.
  Offline: the local cache answers, and the result says it is the phone's copy.
- `remember {text, shared?}` calls `POST /api/member-memories/` with source `phone`. Keep the
  read-through refusal (`REMEMBER_MAIL_REFUSAL`).
- New `forget_memory {id}` ("forget that"): the id of the memory just recalled or saved;
  `DELETE /api/member-memories/<id>`; said as deleted for good only on a 204.
- New `list_memories` for "what do you remember about me".
- A whole-list cache (`GET /api/member-memories/?limit=500`), replaced on each refresh: hard
  deletes leave no tombstone (map decision F). The continuity note reads the cache.
- A 400 from the sensitive filter is said as "that belongs in the ID vault".
- `tools/voice_guide_copy.py` copy for the new tools.

## Verification

- JVM tests for each tool's success and failure words, offline fallback, cache replacement.
