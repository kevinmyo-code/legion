---
map: engine-brain
ticket: "06"
title: "Specialists, code router and router-run reads"
type: build
status: open
size: M
blockers: ["04"]
blocked-by: ["[[04-limb-contract-relay-and-switch]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Specialists, code router and router-run reads

Stage 5 (ADRs 0096, 0097).

## Build
- Specs and prompts for day, money, fleet, places, body + goals, concierge and phone.
- The router lexicon with follow-up stickiness.
- Router-run reads (reads only).
- Suites for money, fleet, body, places, safety and tone. The tone grader is calibrated first.

## Verification (gates)
- [ ] Every suite at its bar; routing tests green; misroute and generalist rates in the trace metrics.
