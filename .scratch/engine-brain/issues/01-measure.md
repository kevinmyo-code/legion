---
map: engine-brain
ticket: "01"
title: "Measure: the engine hop, Gemini from the engine, SSE through Cloud Run, Postgres, cold start, warm at wake"
type: build
status: open
size: S
blockers: ["00"]
blocked-by: ["[[00-rulings]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Measure: the engine hop, Gemini from the engine, SSE through Cloud Run, Postgres, cold start, warm at wake

Stage 0. Nothing a user notices.

## Build
- `server/agents/management/commands/voice_probe.py`, run as a `legion-worker` job execution. It records:
  - 10 streamed flash-lite calls with a generalist-sized prefix: first-token ms;
  - 20 `SELECT 1` and one `list_events`: Postgres round trip;
  - a cold-start request after scale-to-zero.
- A throwaway SSE view behind a flag that emits 5 events 200 ms apart. Gaps are measured from the phone and from curl.
- Phone: log lines `turn_post_ms` and `first_event_ms`, plus a `HEAD /api/agents/warm` sent when Alfred is woken (ADR 0095).

## Verification (gates)
- [ ] A report with p50 and p90 for each number goes to the laptop's reports folder (outside git).
- [ ] SSE events arrive unbuffered: each gap is within 50 ms of 200 ms.
- [ ] Warm-at-wake measured against cold: the first turn after wake pays no cold start in at least 8 of 10 tries, or the report says why.
- [ ] Engine first token from us-south1 is within 150 ms of the phone's 1.0 s median. If not, write the numbers down; it does not block ticket 02.
