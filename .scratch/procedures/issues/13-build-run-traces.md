---
map: procedures
ticket: "13"
title: "Build: run traces, linked to conversation records, with retention"
type: build
status: open
status-detail: ""
blockers: ["06", "11"]
blocked-by: ["[[06-trace-content-and-retention]]", "[[11-build-format-and-tables]]"]
open-blockers: 0
ready: true
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
