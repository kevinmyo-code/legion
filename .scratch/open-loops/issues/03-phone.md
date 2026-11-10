---
map: open-loops
ticket: "03"
title: "Phone: brief line, voice answers, hands path"
type: build
status: built
status-detail: "built 2026-11-10 on feat/open-loops; unit suite green. Owed: a phone build and run (ticket 05)"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Phone: brief line, voice answers, hands path

## What

- `openloops/`: `OpenLoopsController` (one controller for every door), `OpenLoopsBrief` (the line,
  three states), `OpenLoopsSync` (pull the aspect, re-arm), `DjangoOpenLoopsBackend`.
- `BriefController`: the line in the whole `forVoice` brief, a section in the modal.
- `service/OpenLoopsToolbox.kt`: `manage_item` add/remove naming a pending proposal answers it;
  refused in a read-through turn. No new tool declaration.
- `SpokenBriefs.stop`: "stop proposing tasks" flips the engine switch.
- `ui/openloops/OpenLoopsRows.kt` under Digest on Proactive speech: switch, Track, Dismiss.

## Verification

1. The line names task and date, asks, forbids when; none is no line; unreadable forbids (tests).
2. The wake brief turn and the turn after it, and the `get_sitrep` turn: refused, engine untouched; a
   clean later turn: allowed (tests through a real `GeminiLiveSession` flag).
3. Rows: Track and Dismiss reach the engine through the controller; the stop phrase flips the switch
   the row shows (Robolectric Compose test).
4. `LiveSetupPayloadSizeTest`: unchanged, 98,424 chars, ~24,606 tokens.
