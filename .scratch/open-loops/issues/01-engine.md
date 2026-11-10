---
map: open-loops
ticket: "01"
title: "Engine: own words, gate, proposals, endpoints"
type: build
status: built
status-detail: "built 2026-11-10 on feat/open-loops; tests/test_open_loops.py green. Owed: engine deploy with open_loops 0001 and LEGION_OPEN_LOOPS=on"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Engine: own words, gate, proposals, endpoints

## What

- `server/open_loops/`: `words.py` (his own lines from clean turns), `gate.py` (the code checks),
  `service.py` (context block, submit block, list, offer, track, dismiss, settings), models and
  migration 0001 (three tables, owner guard), `/api/open-loops/` routes.
- `member_memory/review.py`: `GET review/context` gains `open_loops`; `POST review/changes` takes
  `open_loops` in the same transaction and adds a counts sentence.
- `TENANT_TABLES`, `OWNER_PATHS`, `DJANGO_MANAGED_TENANT_TABLES`, recruiter routes, openapi.

## Verification

1. Off by default: no words offered, nothing written (test).
2. His words only: assistant lines, tool turns, read-through and carry turns, looks, redaction and
   head unit records never reach the context (tests).
3. The gate refuses an added word, money, a vault number, an unknown label (tests).
4. Dismissed stays dismissed across nights and rewording (test); already on the list is not
   proposed (test); cap across submits, the waiting record read again (test).
5. Track: undated to Todo, dated to an events reminder, idempotent, a past date refused; dismiss for
   good (tests).
6. A Claude token may propose, never answer; recruiter, work laptop, another member and another
   household refused; owner guard in the database (tests, `test_visibility.py`).
