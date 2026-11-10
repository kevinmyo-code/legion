---
map: worker-workflows
ticket: "03"
title: "Engine: workflow kind, catalog cache, name lookup, list"
type: build
status: built
status-detail: "Built 2026-11-10 on feat/worker-workflows: claude_session 0007 (additive; memory_review kept as its own kind), heartbeat catalog, POST tasks/ workflow, GET workflows/, status names, ?origin= and ?kind=. Owed: engine deploy with the migration (Kevin's yes), then ticket 16."
size: M
blockers: ["01", "02"]
blocked-by: ["[[01-rulings]]", "[[02-definition-format]]"]
open-blockers: 1
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

## Build record (2026-11-10, feat/worker-workflows)

| Verification step | State |
|---|---|
| Heartbeat stores a catalog only on a hash change | done (`test_claude_workflows.py`) |
| A bad entry is dropped | done: path, address, amount, bad name, extra key, taken phrase, long schedule |
| Known phrase sets kind, workflow, params | done |
| Unknown phrase 400 lists names | done |
| Scope refusal | done |
| `needs_machine` when two machines online have it | done, plus assign re-checks the catalog |
| Follow-up and cancel unchanged | done; a follow-up inherits kind and workflow |
| Tenancy leak test | done: `test_tenancy.py` green; household B sees no workflows and cannot run A's |
| Matcher parity | done: both matchers run `tools/claude_session/test_fixtures/workflows/match_cases.json` |

Departures from the ticket, each forced by what landed first:
- `kind` already existed (memory-review 0005). 0007 is additive: `workflow` joins the check,
  `memory_review` stays. The rename the ticket describes is ticket 12's, and waits on Kevin.
- `relay` is not added to the check yet (Layer 2 is not built); widening it is one migration.
- `ClaudeMachine.workflows` entries carry `seen_at` (first published), the clock a never-run
  schedule counts its force-after from.
- `POST tasks/` replies `workflow` and `asks` (both always present) so the phone can say "it stops
  for your yes before ..." without a second read.
