---
map: recruiter-demo
ticket: "12"
title: "The phone's tuned turn-taking and barge-in, in the browser"
type: build
status: open
status-detail: "spike first: Silero + Smart Turn in onnxruntime-web on a mid-range phone"
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# The phone's tuned turn-taking and barge-in, in the browser

## Why (Kevin, 2026-11-02)

*"barge in and interrupt we tuned it on my phone no? cant we have the same thing on browser?"*

Today the web assistant uses Google's server VAD (`assistant/live.py`, START_SENSITIVITY_LOW) and
flushes playback on `serverContent.interrupted` (`frontend/src/assistant/live-client.ts`). It works,
but it is not the phone's tuned behaviour (ADR 0064): Smart Turn extend-only end-of-turn (700 ms base,
2.5 s cap) and the Silero barge-in gate (8 qualifying 32 ms windows in any 16, echo floor learned per
reply, 544 ms pre-roll).

## What

- Port `app/.../service/turn/BargeInGate.kt` and `EndOfTurnPolicy.kt` to TypeScript. Both are pure
  folds over 32 ms Silero windows, so they port as logic, with the Kotlin tests ported as the spec.
- Run Silero VAD and Smart Turn v3.2 (same ONNX files as `app/src/main/assets/turn/`) in an
  AudioWorklet + onnxruntime-web (WASM). Mic stays `echoCancellation: true`.
- The engine mints a setup with `automaticActivityDetection.disabled = true` for this mode; the
  browser sends `activityStart` / `activityEnd` itself. The setup stays locked (it is a second
  locked shape, not a client field).
- Server VAD remains the fallback when the models fail to load (old phone, blocked WASM).
- Applies to every web member, not only recruiters.

## Spike first

1. Model download size and first-load time on a mid-range Android Chrome and an iPhone Safari.
2. Smart Turn inference time in WASM per end-of-turn check (phone budget is well under 100 ms).
3. Browser AEC quality on laptop speakers: does the echo floor learn the way it does on the A25?

If (2) is too slow on phones, ship the barge-in gate (Silero only) and keep server end-of-turn.

## Verify

- Ported Kotlin unit tests pass in vitest with the same fixtures.
- A recorded laptop and phone session: Alfred stops within the phone's latency when talked over,
  and does not cut the speaker off mid-sentence on a pause.
