---
map: engine-brain
ticket: "11"
title: "Telemetry: the phone relays the OBD stream to the engine"
type: build
status: open
size: M
blockers: ["04"]
blocked-by: ["[[04-limb-contract-relay-and-switch]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Telemetry: the phone relays the OBD stream to the engine

Stage 10 (ADR 0098).

## Build
- Batched HTTPS ingest.
- Series, point and rollup tables. Every reading carries its source and time.

## Verification (gates)
- [ ] Ingest tests cover provenance, dedupe and downsampling.
- [ ] One real drive's batch is accepted (owed on the phone).
