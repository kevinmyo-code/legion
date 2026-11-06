---
map: body-coach-2
ticket: "11"
title: "What the phone can count for walking, with no watch and no Health Connect"
type: research
status: open
status-detail: "Kevin's cardio is walking; Health Connect stays parked (fat-loss ticket 18)"
blockers: []
blocked-by: []
tags: [ticket]
---
# What the phone can count for walking, with no watch and no Health Connect

Kevin, 2026-11-06: cardio is walking; no watch. Find out, on his phone, what LEGION can count by
itself:

- Android's step counter sensor (`Sensor.TYPE_STEP_COUNTER`): availability on the device, the
  `ACTIVITY_RECOGNITION` runtime permission, reset on reboot, battery cost of a listener.
- Whether a count since midnight can be kept without a foreground service.
- What it would feed: the walking finisher's minutes (ticked by hand today), the energy estimate's
  activity level, the Monday check-in.

Deliverable: a research note in this map and a build ticket if it is worth it. Steps may reach
the engine and Gemini (fat-loss ruling 8); heart rate never.
