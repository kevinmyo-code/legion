---
map: offline-hiking
ticket: 07
title: "Decision: voice with no signal (on-device commands, or nothing)"
type: decision
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Decision: voice with no signal (on-device commands, or nothing)

## Question

Alfred's voice is Gemini Live. No network means no Alfred: no wake-to-conversation, no
understanding, no spoken answers from the model. On a trail with no signal, is any voice worth
building, and if so how much?

## What exists (traced 2026-11-09, `origin/dev` 3afa3eca)

- `service/WakeWordEngine.kt`: Vosk (`vosk-android` 0.3.47) with a runtime-reconfigurable grammar.
  It already recognises a fixed phrase on the phone, offline. The model
  (`vosk-model-small-en-us-0.15`, about 40 MB unzipped, reasoned from the Vosk model list) is NOT
  committed; `app/src/main/assets/vosk-model/README.md` says it is unzipped there before a build.
- `navigation/voice/NavCueSpeaker.kt`: speaks turn cues with Android `TextToSpeech`. Android TTS
  speaks offline only if the engine's voice data for the language is installed on the phone
  (reasoned; Google TTS ships an offline en-US voice on most phones, and it must be checked on the A25).

## Options

| Option | What Kevin gets offline | Effort |
|---|---|---|
| A. Nothing | The hike screen is hands-only. Online, Alfred works as today. | 0 |
| **B. A fixed command set, Vosk grammar + Android TTS** | About eight phrases, each answered by a computed sentence: "where am I" (distance and bearing to car / trailhead), "how far back", "mark this spot", "time to sunset", "how much battery", "stats" (distance, ascent, time), "start / stop recording". No conversation, no model. | M: one grammar, one dispatcher onto the hike controller, the same calls the buttons make (ADR 0035) |
| C. Full on-device ASR (Vosk large, whisper.cpp) + intent parsing | Free speech, still no LLM. | L, and 1 GB+ models, for little more than B |
| D. On-device LLM (Gemini Nano via AICore) | Conversation offline. | The A25 is not on the AICore device list (reasoned, check); out. |

## Recommendation

**A for v1, B for v2.** v1 must be fully usable by hand anyway (ADR 0035 runs the other way here:
every hike action is a button first). B is cheap because the engine already runs on the phone, and
it is genuinely useful with gloves, sweat or a phone in a chest pocket. It is not Alfred and must
not pretend to be: a fixed voice reply in Alfred's register is fine, but the copy never implies a
model heard him.

**The plain statement for Kevin:** offline, nothing understands free speech. Only the listed
phrases work, and the screen says which.

## Rules B would carry

- Activation by a button on the hike screen (push to talk). The wake word stays opt-in and separate;
  a mic open all hike long costs battery for no gain.
- Each answer is computed from the hike controller's state, so it is a fact (§7 outcome verbs).
  "Marked" only after the waypoint row is written.
- Unknown phrase: "I only know the trail commands offline", then the list. Never a guess.
