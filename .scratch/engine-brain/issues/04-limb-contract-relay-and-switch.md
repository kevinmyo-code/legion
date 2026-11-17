---
map: engine-brain
ticket: "04"
title: "The switch: limb contract, relay, SSE turns, per-member flag"
type: build
status: open
size: L
blockers: ["02", "03"]
blocked-by: ["[[02-runtime-and-evals]]", "[[03-engine-homes-for-room-only-data]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# The switch: limb contract, relay, SSE turns, per-member flag

Stage 3. The engine answers turns. The phone keeps its own loop for mail turns until ticket 05.

## Build
- `/api/agents/turn` (SSE), `tool_result`, `cancel` and `warm`.
- A `Limb` table with a capability manifest; contract versions N and N-1 served (ADR 0098).
- The phone as the first limb:
  - an engine-brain client in the cascade;
  - a relay executor over the existing tool dispatch, with confirm gates and call-id dedupe;
  - a per-member flag in Settings plus a server default (ADR 0035 hands path).
- Every turn goes through the generalist and the relay.

## Verification (gates)
- [ ] Every suite runs against BOTH brains on the same cases, and the engine is at or above the phone on each.
- [ ] On-phone smoke: 10 fixed invented turns through the phone's test-turn path over adb. All answer, the relay works, and first sentence comes within today's median + 0.5 s.
- [ ] The flag flips back from Settings and from the server default.
- [ ] A locked phone: each screen-needing relay tool fails in words. This was not determined from code, so check it on the device.
