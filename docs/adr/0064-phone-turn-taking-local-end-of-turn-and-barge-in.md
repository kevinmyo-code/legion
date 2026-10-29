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
  interrupts only when Silero is confident and above the reply's own echo for 256 ms of any
  512 ms (amended 2026-10-28, below). After two
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

## Amendment, 2026-10-28: the trigger is 256 ms of qualifying windows within 512 ms

The barge-in trigger was 256 ms of consecutive qualifying windows (Silero >= 0.8 and above the echo
threshold), one stray window tolerated. On the A25 in call mode (run 5, build `191266c2`) Kevin said
"stop" into the mic over a 12.6 s reply: Silero peaked at 1.00, the level at 30,560 against a
threshold of 3,000, and 27 windows qualified, but Silero's probability jitters across a short plosive
word, so the longest run was 224 ms, one window short, and the reply never stopped. **The gate now
fires when 8 of the last 16 windows (256 ms within 512 ms) qualify, in any order.** A backchannel of
under 256 ms still never fires, and two short ones more than a span apart never add up. A barge-in
now seeds Smart Turn's turn audio and the wire pre-roll with the whole span plus one window
(544 ms), so a word whose onset is up to 512 ms before the fire is not clipped. The self-interrupt
verdict is unchanged.

## Amendment, 2026-10-29: the echo floor learns the echo, not the user

On the A25 in call mode (run 6), the reply after a barge-in began while Kevin was still talking and
learned his voice as its echo (`echo_floor=9785 threshold=14677`); his next words reached 7 of the 8
windows a fire needs, at five times the threshold that had just fired. Every other run 5 and 6 reply
learned 400 to 550, yet each began at the 12,000 default (threshold 18,000). **Two rules now hold.**
For 1 s after the user was last known to be talking (a fire, speech during the verdict watch, or the
phone's `activityEnd`), a window Silero calls speech (>= 0.5) is not learned. And a reply starts from
the floor the previous reply learned in the same session; 12,000 applies only before any reply has
learned one. The trigger and the self-interrupt verdict are unchanged.

**Same day: a normal turn start sends 544 ms of pre-roll, not 400.** In run 5, "stop" said as a
reply ended reached the model as "top". By reading: a start confirms 224 ms after Silero's first
speech window, the ring also holds up to 100 ms of mic buffer after that window, and right after a
drain the policy does not listen for up to ~180 ms, so 400 ms reached only 76 to 176 ms before the
first counted window. 544 ms (the barge-in figure) reaches 220 to 320 ms. Every start now logs a
`pre_roll_ms=` line so the phone can confirm it. Silero's start threshold is unchanged.
