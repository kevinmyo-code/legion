---
map: watches
ticket: "02"
title: "Phone: delivery, voice tool, Watches screen"
type: build
status: built
status-detail: "built 2026-11-10 on feat/watches; JVM tests green. Owed: Kevin's run on the A25 (ticket 04)"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Phone: delivery, voice tool, Watches screen

## What

- `ProactiveCategory.WATCHES`, default on (vetoable). Inside the master switch and quiet hours.
- `WatchFiringPoller` on the Claude result loop's tick (no new loop), every 3 min; one raise per
  poll, ack per firing; a failed ack never repeats a line.
- One `watch` voice tool; set and resume refused in a read-through turn.
- `ui/watches/WatchesScreen.kt`, reached from Proactive speech.

## Verification

- `WatchFiringPollerTest`, `WatchesControllerTest`, `WatchesToolboxTest`.
- `LiveSetupPayloadSizeTest` under its ceiling (numbers in that test's own doc entry, 2026-11-10).
- On the phone: ticket 04.
