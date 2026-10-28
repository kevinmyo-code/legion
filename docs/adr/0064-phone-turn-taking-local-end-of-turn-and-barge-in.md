---
status: accepted
decided: 2026-10-27
decided-by: Kevin
source: "[[decisions#2026-10-27 - Voice: local end of turn (extend-only) and call-style barge-in (Kevin)]]"
tags: [adr]
---

# 64. The phone ends turns locally, and you can talk over Alfred

## Standing

**A phone conversation's turn ends on the phone, not on Gemini's server VAD**, and **on the phone
speaker or a wired headset the user can interrupt Alfred by talking**. Both are settings, both
default on (Settings > Assistant). Server VAD (900 ms, `START_SENSITIVITY_LOW`) is still what runs on
car Bluetooth, when Silero cannot load, or with the switch off.

- **End of turn:** Silero VAD v6.2 decides speech and silence. The turn ends after **700 ms** of
  silence. A Pipecat Smart Turn v3.2 check starts at 192 ms of silence. If it says "not done" before
  700 ms, the turn is held: checks run back to back until one says done, up to a 2.5 s cap. **Smart
  Turn can only extend a turn, never end one early.** No Smart Turn means silence only.
- **Barge-in:** call-style audio (MODE_IN_COMMUNICATION, voice-communication playback and capture
  with AEC), only during a conversation. The mic stays open while Alfred speaks. Speech
  interrupts only when Silero is confident for 256 ms above the reply's own echo. After two
  self-interrupts it turns off for that conversation, and the user is told so.
- **Off on car Bluetooth in v1.** Those sessions keep half-duplex and server VAD.
- **A write tool cancelled by an interruption is reported, never undone.**

## Context

Server VAD has one silence number for every pause. At 900 ms it cut Kevin off mid-thought and was
slow on short questions. Half-duplex meant Alfred could not be stopped by voice. The Live API accepts
`activityStart`/`activityEnd` only with server VAD disabled, so a content-aware end of turn has to
own both. On the A25, Smart Turn took p50 ~165 ms (4 threads) to 280-530 ms (2 threads), with a long
tail. That is too slow to end a turn faster than 700 ms of silence.

## Decision

Local end of turn with a 700 ms silence base, which Smart Turn may only extend. Call-style barge-in
on Silero plus a learned echo floor. Both on by default, off on Bluetooth. Code in
`app/src/main/java/com/kevin/legion/service/turn/`.

## Consequences

- The APK carries a second ONNX Runtime (about 34 MB of arm64 native code) and 11 MB of models,
  because sherpa-onnx's runtime is linked statically and exposes no general session API.
- While a conversation with barge-in runs, the phone is in call audio. The volume keys drive call
  volume, music may pause rather than duck, and dumb mode ignores this mode (`CallAudioMode.ownCommActive`).
- A barge-in starts an activity at once, with its pre-roll. If the trigger was Alfred's own echo,
  that echo is what the model hears. The self-interrupt cut-off limits how often this can happen;
  it does not prevent it.
