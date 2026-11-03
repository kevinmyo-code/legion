---
map: procedures
ticket: "15"
title: "Build: car_check, money_check, morning_briefing as built-ins"
type: build
status: open
status-detail: ""
blockers: ["09", "12"]
blocked-by: ["[[09-first-procedures]]", "[[12-build-runner-and-tool]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Build: car_check, money_check, morning_briefing as built-ins

## What

- The YAML files for the procedures ticket "The first procedures" settles, plus any `compute`
  functions they need in `procedures/steps.py` (e.g. `brief_latest`).
- Each `say` template states every caveat its tools state.

## Verify

- Golden tests on the demo household seed: same input rows, same result text, every run.
- A failed `vehicle_recalls` produces "not known", never "no recalls".
- An UNVERIFIED balance is said UNVERIFIED; a bank-feed balance is not.
