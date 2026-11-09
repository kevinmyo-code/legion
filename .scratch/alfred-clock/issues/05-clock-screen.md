---
map: alfred-clock
ticket: "05"
title: "Clock screen and Matrix home entry"
type: build
status: built
status-detail: "built 2026-11-09 on feat/alfred-clock; clock tests green, full suite and assembleFast not yet run to completion. Owed: Kevin's run on the A25 (ticket 08)"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Clock screen and Matrix home entry

## What

- `ClockActivity`, four tabs: Alarms, Timers, Stopwatch, Bedtime. Launcher alias "Clock".
- Matrix home's big clock opens it (`KeptApps.openAlarms` and the `SET_ALARM` permission retire).
- Banners, each with the system page to fix it: full-screen notifications off, battery
  optimisation on, app background-restricted, notifications refused.
- Roborazzi screenshots of each tab and the ring screen.
