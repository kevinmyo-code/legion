---
map: procedures
ticket: "21"
title: "Build: replay recorded asks against procedures vs free routing"
type: build
status: open
status-detail: ""
blockers: ["10", "15"]
blocked-by: ["[[10-baseline-round-trips]]", "[[15-build-first-procedures]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Build: replay recorded asks against procedures vs free routing

## What

- `tools/procedure_eval.py`: for each recorded car / money / morning turn in the baseline set,
  run the matching procedure on the same household (or the demo seed for a stable fixture) and
  compare: round trips (1 vs measured), engine latency, tool errors, and whether the result
  states every caveat the free-routed answer stated.
- Also report where the phone's local figure (Room / `FleetEngineStore`) and the engine's
  procedure figure DISAGREE (ticket "Engine down").
- Output to `research/eval-<date>.md`, counts and medians only.

## Done when

A table Kevin can read: per ask, before/after round trips, p50/p95 latency, error rate, and
disagreements listed.
