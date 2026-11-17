---
map: engine-brain
ticket: "10"
title: "Procedures: router-direct starts and the device step"
type: build
status: open
size: M
blockers: ["06"]
blocked-by: ["[[06-specialists-router-and-router-run-reads]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Procedures: router-direct starts and the device step

Stage 9 (ADR 0096, amends ADR 0070).

## Verification (gates)
- [ ] Procedure tests cover the `device` step (reads only).
- [ ] `car_check` with a relayed OBD read passes its suite.
