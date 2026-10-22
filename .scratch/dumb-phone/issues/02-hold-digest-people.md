---
map: dumb-phone
ticket: "02"
title: "Build: notification hold, digest, people picker, DND policy"
type: build
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-mode-home-leave]]"]
open-blockers: 1
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
