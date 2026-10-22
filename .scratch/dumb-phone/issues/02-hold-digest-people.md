---
map: dumb-phone
ticket: "02"
title: "Build: notification hold, digest, people picker, DND policy"
type: build
status: built
status-detail: "Suite green 2026-10-22. Owed on the A25: the matcher against real WhatsApp and Messages notifications, setBypassDnd honoured, DND restored exactly, the digest on leave"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Build: notification hold, digest, people picker

Uses the EXISTING `service/MediaNotificationListener.kt`. Chosen people (picked in LEGION from
contacts, not Android starred) get through on any app and are re-alerted over DND; ongoing and
foreground notifications are left alone; everything else is cancelled and COUNTED.

**Read-through only (CLAUDE.md section 7).** The digest stores package, count and time held. Never a
title or text. Nothing from a notification reaches Room or disk. The prototype gist line does not ship.

DND: `INTERRUPTION_FILTER_PRIORITY` while on, previous filter and policy restored exactly on leave.
Bypass-DND channel for chosen people with a one-switch fallback to vibrate and alarm-stream sound.

Verify: person matcher, hold/let-through decision table, digest counting, DND save/restore tests.
Owed on the phone: matcher against real WhatsApp and Messages notifications, setBypassDnd honoured.

## What was decided while building (not in Kevin's rulings; each is one line to undo)

- **LEGION's own notifications are left alone**, except the Tetris invite, which is let through only when
  its sender matches a chosen person (matched by whole-word name overlap on the household name).
  Reminders and hazard raises are the user's own and LEGION is the home app.
- **Group summaries, ongoing, foreground-service, non-clearable and call/navigation/media/alarm
  notifications are left alone.** A summary is left so its children are cancelled one by one.
- **Emergency-alert receivers (package contains `cellbroadcast` or `emergency`) are never held.**
- **A group chat's title is never matched against a name**; only a sender person in it can match.
- **The re-alert carries only the chosen person's name and the app's label**, never a word of the
  message. The original notification stays in the shade, silent under the Priority policy.
- **Dumb mode and Quiet both on:** this slice writes the global policy as the brief says; Quiet uses a
  zen rule. The more restrictive wins while both are on; on leaving, the global state returns to what
  it was when dumb mode began. Not tested on hardware.
