---
map: procedures
ticket: "10"
title: "Baseline: measure round trips, latency and tool errors from recorded conversations"
type: task
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Baseline: measure round trips, latency and tool errors from recorded conversations

## Question

Before any runner exists, what do car, money and morning asks cost today, measured, not traced?

## What

- Pull `reason=conversation` records from the engine: `manage.py device_logs --reason
  conversation --render` (`server/devicelogs/management/commands/device_logs.py`), phone, head unit
  and web.
- Parse with the LIVE_AUDIT grammar (`tools/voice_audit.py` docstring 1-30). Per conversation turn:
  number of tool calls, model round trips (sub-agent POSTs included where logged), wall time from
  end of user speech to first assistant audio, tool errors (`success=false`, refused, timeouts,
  cancels).
- Classify turns into car / money / morning / other by tool names.
- Output a table into `research/baseline-<date>.md`. **Counts and medians only; no transcript text
  is copied into the repo** (the repo is public).

## Done when

The table exists with sample sizes stated, and the latency budget in ticket "How each client
reaches run_procedure" is confirmed or corrected.

AFK. Needs engine access to read the records.
