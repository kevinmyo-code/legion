---
map: proactive-mode
ticket: 14
title: "No cap, speech with the screen off, the two spoken briefs, and the emergency alarm"
type: build
status: built
status-detail: "2026-11-10, Kevin - four rulings built on feat/proactive-voice (ADR 0082); owes Digest on, then a phone run"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# No cap, speech with the screen off, the two spoken briefs, and the emergency alarm

Kevin, 2026-11-10, four rulings. Full text and the picks he did not rule on:
`memory/library/decisions.md` 2026-11-10 and `docs/adr/0082-proactive-speech-uncapped-and-briefs.md`.

1. *"no cap on proactive spoken lines"* - decision 15 superseded, cap code deleted.
2. *"can speak at screen off. unless i silent the phone ofc"* - decision 17's screen-on half
   superseded; the gate is not-silenced plus no live meeting.
3. Yes to "spoken brief at wake-up or arriving home" - `spokenbrief/WakeBrief.kt` and
   `spokenbrief/HomeBrief.kt`, both Digest, both stoppable in one instruction.
4. *"also quiet hours, if any emergency in the area is noted, alarm me"* - `ProactiveUrgency.Alarm`
   on Safety raises, rung on the clock's ring path. The feed is `feat/conditions`'s.

## Owed on the phone (none of this has run on hardware)

1. **Turn Digest on** in Settings, Proactive speech (it ships off; both briefs are silent until then).
2. Screen off, phone locked, ringer on: a Timing reminder is SPOKEN, not posted. Then ringer on
   vibrate: the same reminder is POSTED.
3. A raise that asks something (a Claude result with a question) with the screen off: spoken; whether
   the reply mic opened is in `LIVE` logs. Expected: open only if LEGION was opened since the last
   reboot.
4. A morning alarm, Dismiss: the brief is spoken without a tap. Dismiss a second alarm the same
   morning: nothing. Brief me still replays.
5. Leave home past the geofence, come back: the home brief, once. A reboot at home: no brief.
6. "Stop the morning brief": the row under Digest reads off; the next morning's dismiss is silent.
7. The emergency ring needs `feat/conditions`; until then only the unit tests cover it.
