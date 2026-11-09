---
map: worker-workflows
ticket: "03"
title: "Engine: workflow kind, catalog cache, name lookup, list"
type: build
status: open
status-detail: ""
size: M
blockers: ["01", "02"]
blocked-by: ["[[01-rulings]]", "[[02-definition-format]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Engine: workflow kind, catalog cache, name lookup, list

`server/claude_session/`, additive migration.

## Tables

- `ClaudeTask.kind` text, default `ask`, check in (`ask`, `relay`, `workflow`). `relay` is the
  Layer 2 design's; `memory_review` is not a kind (see [Catch-up runs](04-catch-up-runs.md)).
- `ClaudeTask.workflow` text null (the name; required when `kind = workflow`, check constraint).
- `ClaudeTask.params` text null (Kevin's words after the alias, at most 500 chars).
- `origin` gains `schedule`.
- `ClaudeMachine.workflows` JSON (the published catalog, `catalog_for_engine` shape),
  `workflows_hash` text, `workflows_at` timestamp.

If the memory-review map's "Due rule and the memory_review task kind" lands first with
`kind = "memory_review"`, this migration rewrites those rows to `kind = workflow`,
`workflow = "memory-review"` and narrows the check. If this lands first, that ticket uses this
shape. Either way one column, one meaning.

## API

| Method and path | Change |
|---|---|
| `POST machines/heartbeat/` | Accepts `workflows_hash` always and `workflows` (list, at most 50, each at most 2 KB) when the worker's hash differs from the one the reply last echoed. Reply adds `workflows_hash` (what the engine holds). Lint-equivalent checks on the engine: a row that fails (path-like text, bad name) is dropped and counted, never stored |
| `POST tasks/` | Accepts `workflow` (string). Phone voice sends Kevin's phrase; screen and web send the exact name. Resolution: the machine's catalog (named machine, else the only online one, else `needs_machine` as today), `match()` from ticket 02. Hit: `kind = workflow`, `workflow`, `params`; `request` keeps Kevin's words. Miss: 400 `{"detail": "The Alienware has no workflow called that. It has: log audit, ship round, memory review.", "workflows": [names]}`. A workflow whose `scope` excludes the machine: 400 in words. `deliver` from the catalog's `deliver` or `outputs` when the request did not set one |
| `GET workflows/?machine=` | New. `[{machine, name, says, aliases, params: [{name, says}], asks: [says], outputs, every, last_run: {task, status, finished_at} or null, due: bool}]`, newest catalog per machine, offline machines included and marked |
| `GET status/` | Each machine adds `workflows: [{name, says}]` (for the phone's `check`) |
| Task rows | Add `kind`, `workflow`, `params` |

## Rules

- Match is whole-word, longest alias wins, on the catalog the machine last published. A catalog is
  shown even when its machine is offline (the run queues, as any task).
- `confirms` on a workflow follow-up behaves exactly as today. A workflow task never carries a
  standing `confirms`.
- No log line carries `request`, `params`, `says` or aliases (ADR 0067 logging rule).
- `workflows` is part of `TENANT_TABLES` coverage through `claude_machines` (already tenanted).

## Verification

- [ ] pytest: heartbeat stores a catalog only on a hash change; a bad entry is dropped; create
      with a known phrase sets kind, workflow, params; unknown phrase 400 lists names; scope
      refusal; `needs_machine` when two machines are online and the phrase is on both; follow-up
      and cancel unchanged for workflow tasks; tenancy leak test still green.
- [ ] Matcher parity: the engine runs ticket 02's shared phrase table.
