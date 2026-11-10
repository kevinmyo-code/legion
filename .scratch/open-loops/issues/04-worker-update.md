---
map: open-loops
ticket: "04"
title: "Worker update and a live night"
type: test
status: open
status-detail: "owed by Kevin: order a worker update; then one night with LEGION_OPEN_LOOPS=on"
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Worker update and a live night

## Steps

1. Kevin orders a worker update: the Alienware worker restarts from a checkout carrying
   `feat/open-loops` (`memory_review_prompt.md`, `review_memories.py`, `worker.py`).
2. The engine is deployed with `open_loops.0001_initial`, and `LEGION_OPEN_LOOPS=on` with the memory
   review on.
3. After one night: `GET /api/open-loops/` on the phone lists what was proposed; the review task's
   summary ends with "Open loops: N to-dos proposed." and carries no task text.
4. A dry run first is possible: `review_memories.py submit --task N --dry-run` prints loop verdicts.
