---
map: engine-brain
ticket: "09"
title: "Derived knowledge: five baselines and the change log; the greeting picker moves to the engine"
type: build
status: open
size: M
blockers: ["06"]
blocked-by: ["[[06-specialists-router-and-router-run-reads]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Derived knowledge: five baselines and the change log; the greeting picker moves to the engine

Stage 8 (ADR 0101).

## Build
- Nightly `compute_baselines` for the five baselines, each with its minimum sample.
- A `change_events` table fed by the watch evaluator, with a link from watch firings and a told-ledger view.
- Pull-based tools `baselines` and `changes_since`.
- "Forget" deletes the item and adds a suppression key.
- The greeting picker moves to the engine as their first consumer.

## Verification (gates)
- [ ] A unit test per metric and per detector: window edges, minimum sample, private-input propagation, dedupe, suppression.
- [ ] The `derived` suite and the greeting suite at their bars.
