---
map: open-loops
ticket: "02"
title: "Worker: loops.json in the nightly review"
type: build
status: built
status-detail: "built 2026-11-10 on feat/open-loops; tools/claude_session suite green. Owed: worker update (ticket 04)"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Worker: loops.json in the nightly review

## What

- `review_memories.py`: fetch says whether open loops are on and where to write; submit reads an
  optional `loops.json`, validates W labels locally, sends `open_loops`, prints loop verdicts without
  text on a dry run, deletes loops.json with the other files.
- `worker.py`: `loops.json` joins `REVIEW_TEXT_FILES` (deleted at a review's finish).
- `memory_review_prompt.md`: the open-loops section (his words only, his own commitments, his words
  for the task, no money, no worked-out dates).

## Verification

1. Round trip sends loops and deletes every text file; a bad loop fails before any POST; loops while
   off fail locally; a dry run prints verdicts without text (tests, fake engine).
2. The worker deletes loops.json at finish; a sentinel never reaches stdout, the log, a heartbeat or
   the finish payload (test).
