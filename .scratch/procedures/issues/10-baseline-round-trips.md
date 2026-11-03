---
map: procedures
ticket: "10"
title: "Baseline: measure round trips, latency and tool errors from recorded conversations"
type: task
status: built
status-detail: "measuring command built and tested on fixtures; owes Kevin's run on the deployed worker and the table in research/"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
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

## Built (2026-11-03, branch `feat/procedures-foundation`)

- `manage.py conversation_baseline` (`server/devicelogs/baseline.py`, the column definitions in
  its docstring; tests `tests/test_conversation_baseline.py` on fixtures in each writer's line
  format). Prints Markdown: asks by device and class (round trips, first audio, answer, errors),
  tool errors by kind, per-tool latency, and the engine-side budget estimate from web records.
  No transcript text, arguments or results are printed (a test pins it).
- Device rows are `device_label` (phone and head unit both report `MANUFACTURER MODEL`), `web`,
  and with `--include-visits` `web (recruiter visit)`.
- Known gaps, stated in the output's definitions: the head unit logs no sub-agent line, so its
  `ask_engine` POSTs are counted as unlogged, and an `ask_engine` ask is classed by its question's
  words, not its tools.

Kevin runs (prod reads are his):

```
gcloud run jobs execute legion-worker --region us-south1 --wait --args=manage.py,conversation_baseline,--days,60,--numbered
```

then reads the execution's stdout from Cloud Logging, sorted by the 4-digit prefix, and pastes it
into `research/baseline-<date>.md`.

## Done when, accounted for

- Table in `research/`, sample sizes stated: **owed**, Kevin's run (the command prints `n`).
- Ticket 04's latency budget confirmed or corrected: **owed**, from the budget section of that run.
