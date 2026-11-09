---
map: alfred-clock
ticket: "02"
title: "Ringing: foreground service, full-screen intent, audio, snooze, auto-silence"
type: build
status: built
status-detail: "built 2026-11-09 on feat/alfred-clock; clock tests green, full suite and assembleFast not yet run to completion. Owed: Kevin's run on the A25 (ticket 08)"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Ringing: foreground service, full-screen intent, audio, snooze, auto-silence

## What

- Fire receiver starts `RingService` in the foreground. Type `systemExempted` on Android 14+
  (allowed for apps holding `USE_EXACT_ALARM`), `mediaPlayback` below.
- Its notification carries a full-screen intent to `RingActivity` (shows over the lock screen,
  turns the screen on). Android 14+: `NotificationManager.canUseFullScreenIntent()`; when refused
  the same notification arrives heads-up with Dismiss and Snooze, and the Clock screen says so.
- Audio: the default alarm sound on `USAGE_ALARM`, ramped over 30 s, vibrating. Alarm stream at 0
  is raised to half for the ring and put back after.
- Snooze 9 min (re-armed with `setAlarmClock`). Auto-silence after 10 min posts "Missed alarm".
- Direct-boot aware: service, receiver and activity run before the first unlock.
