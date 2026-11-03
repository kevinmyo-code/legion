---
map: procedures
ticket: "13"
title: "Build: run traces, linked to conversation records, with retention"
type: build
status: built
status-detail: "built 2026-11-03 on feat/procedures-runner, suite green bar the known dev failures; owes deploy"
blockers: ["06", "11"]
blocked-by: ["[[06-trace-content-and-retention]]", "[[11-build-format-and-tables]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Build: run traces, linked to conversation records, with retention

## What

- `procedure_runs` (household, user, procedure version, caller kind `phone` / `headunit` / `web` /
  `recruiter` / `mcp`, conversation_id, status `ok` / `partial` / `failed` / `waiting` /
  `cancelled`, started_at, ms) and `procedure_steps` (run, step id, kind, tool, args, trimmed
  result, outcome, branch, chosen_by, started_at, ms; model and token counts for `decide`).
- One `procedure_run` line written into the caller's conversation record when a
  `conversation_id` is present (`devicelogs/web_conversation.py` writer for web; the phone and
  head unit log their own LIVE_AUDIT line for the tool call as today).
- `tools/voice_audit.py` and `devicelogs/voice_audit.py` render the line.
- Prune: members 60 days via a `deploy/crontab` line; visit runs CASCADE with the visit household
  and the 30-day cap.
- Recruiter visit tables appear in `demo/visit_cleanup.py`'s FK order automatically (it reads FKs);
  test it.

## Verify

- A run with 3 steps writes 1 run + 3 step rows; a failed step stores outcome `failed` and its
  sentence.
- Visit delete removes its runs; prune removes a 61-day-old member run and keeps a 59-day one.

## Built (2026-11-03, branch `feat/procedures-runner`)

- `public.procedure_runs` (household, user SET_NULL, version PROTECT, caller kind, conversation
  id, status `ok` / `partial` / `failed` / `waiting` / `cancelled`, inputs, started_at, ms, the
  trimmed result; resume state held ONLY while waiting, CHECK-enforced) and
  `public.procedure_steps` (run, household, seq, step id, kind, tool, args as sent, trimmed
  result, outcome `ok` / `refused` / `failed` / `asked`, branch, `chosen_by` code / model / user,
  started_at, ms; model, token counts and the labelled cost estimate for `decide`). Migration
  `0002` in `public`, both in `TENANT_TABLES`.
- One `procedure_run run=... name=... version=... status=... steps=... ms=... branches=...`
  line in the caller's web conversation record (`devicelogs.web_conversation.
  record_procedure_run`), beside the tool door's own lines; `conversation_id` from the argument
  or the web door's body. `tools/voice_audit.py` and `devicelogs/voice_audit.py` render it.
- Retention (`procedures/retention.py`): members 60 days and recruiter runs 30, pruned inline at
  every new run (the `devicelogs.views.prune` pattern, no new crontab line) and by
  `manage.py prune_demo_visitors` hourly; expired waits cancelled. Visit runs CASCADE with the
  visit household and `demo.visit_cleanup` orders them from the foreign keys.

## Verify, accounted for (CLAUDE.md L11)

- A 3-step run writes 1 run + 3 step rows; a failed step stores `failed` and its sentence:
  **done**.
- Visit delete removes its runs (FK order tested); prune removes a 61-day member run and keeps a
  59-day one; a recruiter run is capped at 30: **done**.
- Tenancy: rows carry the caller's household (`test_procedure_runs_are_scoped_by_household`);
  no read route exists until ticket 14: **done** for the rows, the route's leak test is 14's.
- Phone and head unit lines: theirs to log (tickets 16, 17).
- On the deployed engine: **owed**.
