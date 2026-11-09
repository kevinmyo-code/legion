---
map: alfred-clock
ticket: "03"
title: "Timers and stopwatch"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Timers and stopwatch

## What

- Named timers, several at once. Start, pause, resume, cancel, how long left.
- Ongoing countdown notification per running timer (`setUsesChronometer` + count-down, no polling)
  with Pause/Resume and Cancel. Done rings through the same `RingService` (Stop, +1 min).
- Timers run on elapsed time within a boot and fall back to wall-clock after a reboot.
- One stopwatch: start, stop, lap, reset. Survives process death; a reboot stops it at its total.
