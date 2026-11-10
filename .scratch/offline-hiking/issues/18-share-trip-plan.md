---
map: offline-hiking
ticket: 18
title: "Build: share a trip plan before leaving signal"
type: build
status: open
status-detail: ""
blockers: ["09"]
blocked-by: ["[[09-trip-plan-and-overdue-alert]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Build: share a trip plan before leaving signal

**Release:** v1. **Size:** S.

## What

Ticket 09 option A (and C if ruled): a "Share trip plan" button on the hike screen that builds a
short text and opens Android's share sheet. Kevin picks who and presses send himself.

## Rules

- Text: area name, start time, expected return, the planned GPX name if one is loaded, the car
  waypoint as a plain map link, and one line on what to do if he is not back.
- LEGION never sends it. The share sheet does, on Kevin's tap. Nothing is said as "sent".
- The safety card on the hike screen, once: "This phone has no satellite SOS. With no signal it
  cannot call for help. For backcountry, carry a satellite messenger."
- Option C (if ruled): one local reminder to Kevin at the expected return time, silenceable in one
  switch.

## Verification

- Unit: text builder with and without a car waypoint and GPX, and the no-return-time case.
- Compile, suite green. On the phone: the share sheet opens with the text. Owed to ticket 19.
