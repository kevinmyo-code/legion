---
map: offline-hiking
ticket: 23
title: "Build: offline trail commands by voice (Vosk grammar, Android TTS)"
type: build
status: open
status-detail: ""
blockers: ["07", "15", "16"]
blocked-by: ["[[07-offline-voice]]", "[[15-track-recorder-and-backtrack]]", "[[16-waypoints-bearing-distance]]"]
open-blockers: 3
ready: false
tags: [ticket]
---
# Build: offline trail commands by voice (Vosk grammar, Android TTS)

**Release:** v2, only if ticket 07 rules B. **Size:** M.

## What

Push-to-talk on the hike screen. A fixed Vosk grammar of about eight phrases, each dispatched onto
the hike controller (the same calls the buttons make, ADR 0035) and answered with Android TTS.

## Rules

- Reuse the Vosk model and engine that `service/WakeWordEngine.kt` already loads; a second
  `Recognizer` with the trail grammar, not a second model.
- Answers computed from controller state: distance and bearing to the car, distance back along the
  track, time to sunset, battery, stats. "Marked" only after the row is written (§7).
- Unknown phrase: the list of what works offline. Never a guess.
- Before shipping, check on the A25 that the TTS engine speaks with no network (voice data installed).

## Verification

- Unit: phrase-to-command table, each answer sentence from a fake controller state.
- Compile, suite green. On the phone in airplane mode: each phrase answered. Owed.
