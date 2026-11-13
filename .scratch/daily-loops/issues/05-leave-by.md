---
map: daily-loops
ticket: "05"
title: "Leave-by: notification and Alfred says it"
type: build
status: built
status-detail: "built 2026-11-13: leaveby/ (3 h window, traffic drive via the Nav SDK seam, leave-by = start - drive - 10 min, recheck 5 min before), notification + proactive speech (Timing), day-view line, Settings switch; 628 tests green, detekt 290. Owed on the phone: a real event with a location, the alarms firing, notification and speech, the geocode hit. Gaps: no spoken stop instruction, not armed after reboot until the app runs, declined events not excluded (no column)"
blocked-by: []
tags: [ticket]
---

# Leave-by times

For a calendar event with a location: the drive time from the phone's position with live traffic
(Mapbox, the household's own token, ADR 0054), a leave-by time shown on the event and on HOME, a
notification at leave-by, and Alfred says it when a conversation is open or the phone is in the car.
Anchored to the event and the traffic figure (compulsion test a); one per event; silenced per event
or for good in one instruction. Travel time is an estimate and is said as one. No location fix:
says it cannot work out a leave-by time, never guesses one. See mapbox-plus ticket 07.
