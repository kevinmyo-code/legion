---
map: alfred-clock
ticket: "01"
title: "Alarm engine: model, math, store, scheduling, re-arm"
type: build
status: built
status-detail: "built 2026-11-09 on feat/alfred-clock; clock tests green, full suite and assembleFast not yet run to completion. Owed: Kevin's run on the A25 (ticket 08)"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Alarm engine: model, math, store, scheduling, re-arm

## What

- `ClockAlarm`: hour, minute, repeat days (empty = once), label, on/off, snoozed-until.
- Next trigger from now in the phone's zone. Repeat days pick the next matching day. A one-off
  is today if the time is still ahead, else tomorrow.
- **DST:** a time in the spring-forward gap moves later by the gap (02:30 that does not exist
  rings at 03:30, java.time's rule). In the fall-back overlap it rings once, at the earlier offset.
- **Store:** JSON in device-protected prefs, so a reboot rings before the first unlock.
- **Scheduling:** `AlarmManager.setAlarmClock` per enabled alarm (status-bar icon, Doze-exempt),
  one PendingIntent per alarm id. Cancel on off or delete.
- **Re-arm (idempotent `rescheduleAll`)** on: `LOCKED_BOOT_COMPLETED`, `BOOT_COMPLETED`,
  `TIME_SET`, `TIMEZONE_CHANGED`, `MY_PACKAGE_REPLACED`, exact-alarm grant change, app start.
- A one-off whose time passed while the phone was off is reported as missed, not rung late.

## Tests

Unit: repeat days, one-off today/tomorrow, DST gap and overlap, zone change, snooze, store round
trip. Robolectric: the reschedule receiver re-arms from the store on each action.
