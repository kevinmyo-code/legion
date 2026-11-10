---
map: worker-workflows
ticket: "05"
title: "Worker: publish the catalog, workflow task message, reports folder, task log"
type: build
status: built
status-detail: "Built 2026-11-10 on feat/worker-workflows: catalog publish on hash change, workflow task message, report folder, scheduled runs tokenless, email_self only from the local sidecar, task log with 180 day prune. Owed: Kevin's order to update the live worker (legion-claude-on) after the engine deploy."
size: M
blockers: ["02", "03"]
blocked-by: ["[[02-definition-format]]", "[[03-engine-catalog-and-trigger]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Worker

`tools/claude_session/worker.py`, on top of `fix/claude-worker-wait` once it merges (the live
worker). Stdlib only, as today.

## Catalog

- At start and every 5 minutes: `workflows.load_catalog(<work>\.claude\skills)`, lint, hash.
  Heartbeat carries `workflows_hash`; the full `workflows` list only when the engine's echoed
  hash differs. A sidecar that fails lint is left out and logged by name only.
- Skill changes are live for helpers already (folder existed at session start); the catalog
  follows within 5 minutes.

## A workflow task's message

`task_message` gains a branch for `kind == "workflow"` (invented example):

```
[task 52] Workflow log-audit. Use the log-audit skill.
Kevin's words: "audit my logs from this morning"   (or: Scheduled run; nobody is waiting on this.)
Params text: "from this morning"
Read %USERPROFILE%\.legion\AGENT-RULES.md first.
Report file: %USERPROFILE%\.legion\reports\log-audit\2026-11-09-task-52.md (a new file; list it with --found).
This workflow stops for Kevin's yes at: deploy.   (from asks; omitted when empty)
Work folder: ...
```

- **Scheduled runs get no token of any kind**, and the message says: "Anything on the ask list:
  stop with needs_confirmation. Do not ask more than one question." (R3)
- `deliver: email_self` from the catalog issues the same one-send token today's `email_self`
  path issues, to Kevin only (R3's one standing yes).
- `main_session_prompt.md` rule 11 gains one line: a `Workflow <name>` message always starts the
  helper with "Use the <name> skill", never a different skill.

## Reports folder

- `%USERPROFILE%\.legion\reports\<workflow>\<YYYY-MM-DD>-task-<N>.md`, created by the worker
  before the helper starts (folder only). New dated files pass the ask-list hook (not an
  overwrite); a helper overwriting an older report asks, as for any of Kevin's files. The hook
  gets this folder added to its "Kevin's files" list explicitly, with a test.
- Prune: reports older than 180 days, by the worker's existing prune.
- Not to be confused with `%APPDATA%\legion-claude\reports\`, which is report.py's queue.

## Task log (for the miner, R4)

- At every finish: append one line to `%USERPROFILE%\.legion\task-log.jsonl`:
  `{"date", "task", "kind", "workflow", "origin", "status", "request"}`. Request text stays on the
  laptop; the file is pruned to 180 days. Nothing else reads it but the miner.

## Verification

- [ ] `test_worker.py` (fake engine, fake claude): catalog published on change only; a workflow
      task message carries skill, words, report path, asks; scheduled task gets no token;
      `email_self` workflow gets the Kevin-only token; task log line written at finish and pruned.
- [ ] `test_asklist_hook.py`: a new report file passes; overwriting an older one asks.

## Build record (2026-11-10, feat/worker-workflows)

| Verification step | State |
|---|---|
| Catalog published on change only | done (`test_worker_workflows.py`), plus an engine that echoes no hash gets the list at most every 5 minutes |
| Workflow message carries skill, words, report path, asks | done |
| Scheduled task gets no token | done |
| `email_self` workflow gets the Kevin-only token | done, and only when the laptop's own sidecar declares it (an engine row alone does not) |
| Task log line written at finish and pruned | done; reports pruned only by the worker's own name pattern under a workflow the laptop has |
| `test_asklist_hook.py`: new report passes, overwriting an older one asks | done; no new hook rule was needed (the folder is outside the work folder, so it is Kevin's), the docstring says so |

Also: a workflow the laptop no longer has fails in words and never runs as free text; a
follow-up names the workflow's skill again; tests point `USERPROFILE` and every new path at a temp
folder, so a test run never writes Kevin's real `.legion` folder.
