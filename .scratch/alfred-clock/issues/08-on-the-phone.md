---
map: alfred-clock
ticket: "08"
title: "Acceptance on the A25, and what is deferred"
type: acceptance
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Acceptance on the A25, and what is deferred

## Kevin's run

1. Install, open Clock: banners show anything missing. Grant battery exemption.
2. Alarm 2 min out, lock the phone, wait: full screen over the lock screen, ramping sound.
3. Snooze, then dismiss. Brief me speaks the sitrep.
4. Reboot with an alarm set, do not unlock: it still rings.
5. "Alfred, set a timer for 5 minutes called pasta", "how long left on pasta".
6. Bedtime 1 min out: Quiet and the feed block on; dismissing the next alarm turns them off.

## What Kevin grants on the phone

- Battery: LEGION unrestricted / not optimised (the Clock screen's "Exempt LEGION" button). On
  Samsung also check LEGION is not in Sleeping or Deep sleeping apps (Settings, Battery,
  Background usage limits); the app cannot read that list.
- Full screen notifications for LEGION: granted at install when sideloaded; the Clock screen asks
  if it is off.
- Do Not Disturb access (already granted if Quiet works): Bedtime's Quiet needs it.
- Notifications on. Exact alarms need nothing (`USE_EXACT_ALARM`).

## Deferred (not v1)

- Custom ringtones per alarm, sunrise screen, world clock, alarm missions/puzzles.
- Skip next occurrence of a repeating alarm.
- Head unit: nothing.
