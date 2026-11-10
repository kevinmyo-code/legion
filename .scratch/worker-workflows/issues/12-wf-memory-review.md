---
map: worker-workflows
ticket: "12"
title: "Workflow: memory-review (the wrapper; the review is the memory-review map)"
type: build
status: open
status-detail: "Deferred 2026-11-10: needs Kevin's ruling. The memory review shipped first as kind memory_review, and the live worker and ask-list hook guard it by that kind; renaming it to kind workflow ships only with a worker update. Until then it keeps its own kind and minting, and a workflow mint never stacks on it (one per claim)."
size: S
blockers: ["04", "05"]
blocked-by: ["[[04-catch-up-runs]]", "[[05-worker]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Workflow: memory-review

The daily review of LEGION's member memories (merge duplicates, resolve contradictions, retire
stale events) is designed in the memory-review map (local branch `docs/memory-review-claude`,
not edited here). This ticket only makes it one workflow:

- Skill `memory-review` in the private folder: "run `tools/claude_session/review_memories.py` as
  that map's worker ticket says". The helper prompt, the no-text guards and the script are that
  map's.
- `workflow.json`: `name: memory-review`, `says: "Tidies Alfred's memories: duplicates,
  contradictions, past events."`, aliases "review my memories", "memory review"; no params; no
  asks (the engine gates every change; proposals wait for Kevin on the phone); outputs `summary`;
  `every: {hours: 24, idle_minutes: 10, force_after_hours: 36, retry_after_hours: 6}` (that map's
  numbers).
- Engine: the memory review's extra due predicate registered under `memory-review`
  ([Catch-up runs](04-catch-up-runs.md)); its `review/run` endpoint becomes a call to
  `workflows/run/`.
- `progress` for this workflow is the fixed phrase "Reviewing memories" (that map's rule).

## Verification

- [ ] That map's due-rule tests pass with `kind = workflow, workflow = memory-review`.
- [ ] One catch-up run after a cold boot on the Alienware (ticket 16).
