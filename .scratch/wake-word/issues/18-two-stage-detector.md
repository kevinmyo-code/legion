---
map: wake-word
ticket: 18
title: "A two-stage wake detector, the way Siri does it"
type: build
status: built
status-detail: "Built 2026-10-03, suite green, owing the A25 run: trigger rate over 20 tries quiet and with music, false opens over 30 min of TV, overnight battery vs the 21 mA baseline. VAD and KWS thresholds are guesses; excelsior tokenises to six BPE pieces and may spot poorly."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# A two-stage wake detector, the way Siri does it

**Kevin, 2026-10-03:** *"lets try it that way, how siri does it."*

## Why

Today `service/WakeWordEngine.kt` runs Vosk (`vosk-android` 0.3.47, a ~40 MB general recognizer
restricted to a grammar) on every audio frame, with no voice-activity gate, and rebuilds the
recognizer after ~1.5 s of handoff trouble. Siri keeps cost low with a cheap first pass on an
always-on processor and a larger second pass only on a candidate
([Apple ML Research](https://machinelearning.apple.com/research/hey-siri)). Without access to the
phone's DSP for a custom phrase (that is spike [[19-dsp-hotword-spike]]), the same shape can run on
the CPU:

1. **Stage 0, voice activity gate.** A small VAD (sherpa-onnx ships Silero VAD) decides whether
   anyone is speaking. Silence never reaches stage 1.
2. **Stage 1, keyword spotter.** sherpa-onnx keyword spotting (a few MB, phrase set from a keywords
   file, no retraining) listens for "hey <companion name>" only while stage 0 says speech.
3. **Stage 2, confirm.** On a stage-1 hit, re-check the buffered audio before opening Gemini: run the
   existing Vosk grammar over the last ~2 s (Vosk stays in the build for this only), and open the
   session only if both agree. A stage-1 hit that stage 2 rejects is logged, never opens Gemini.

## Rules that hold

- The companion name drives the phrase exactly as ticket 09 made it (never hardcoded; a blank name
  refuses to start, in words).
- Ticket 08/15: a deaf or silenced mic is still visible; the levels/watchdog keep working across the
  new pipeline.
- Ticket 10's acknowledgement and ticket 11's "never mind" are unchanged.
- Models are bundled in `assets/` (CLAUDE.md §7: never fetched at runtime). Record their size and
  licence in the commit.
- A setting (dev-visible is fine) can fall back to the old Vosk-only path until the A25 run proves
  the new one; default to the new pipeline once it has.

## Verification

- Unit: the stage machine (silence -> no KWS work; KWS hit + confirm -> open; KWS hit + reject ->
  no open, logged).
- On the A25: trigger rate on the real phrase across 20 tries in a quiet room and with music; false
  opens over 30 minutes of TV; battery overnight with it on versus the 2026-10-03 baseline of about
  21 mA idle with the wake word off (ticket 03's measurement).
