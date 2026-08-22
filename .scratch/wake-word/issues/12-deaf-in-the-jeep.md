---
map: wake-word
ticket: "12"
title: "Deaf in the Jeep, fine outside it"
type: task
status: built
status-detail: "Built 2026-08-20 together with ticket 08. The engine now opens VOICE_COMMUNICATION with hardware effects, confirmed in logcat on the A25. UNVERIFIED in the actual Jeep - that needs a drive."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Deaf in the Jeep, fine outside it

## Question

Kevin, 2026-08-20, the first time he used the working wake word in the world: *"just has a less
sensitive input than usual voice, like in the car (jeep) it didnt activate at all, but outside it
did."*

**The car is the whole point of this feature.** A wake word that works standing in a driveway and
not while driving has failed at the only place it matters.

### Traced, not guessed

The comparison Kevin made is the diagnosis. Ordinary tap-to-talk hears him fine in the Jeep; the
wake word does not. They do not use the same microphone configuration.

| Path | Audio source | Processing |
|---|---|---|
| `GeminiLiveSession` (works in the car) | `VOICE_COMMUNICATION`, plus `privacySensitive` | Echo cancellation, noise suppression, automatic gain - telephony-tuned |
| `WakeWordEngine` via Vosk `SpeechService` | `VOICE_RECOGNITION`, **hardcoded** | Deliberately minimal - ASR engines are usually fed raw audio on purpose |

`vosk-android-0.3.47-sources.jar`, `SpeechService.java:57`:

```java
recorder = new AudioRecord(
        AudioSource.VOICE_RECOGNITION, this.sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2);
```

No parameter, no setter, no builder. **The engine cannot choose its own microphone while
`SpeechService` owns the `AudioRecord`.**

### Two candidate causes, and they are separable

1. **Processing.** `VOICE_RECOGNITION` suppresses far less road noise than `VOICE_COMMUNICATION`,
   so a small model that copes in a quiet room is swamped at 70mph.
2. **Routing.** With the phone paired to the Jeep, `VOICE_COMMUNICATION` can route to the car's
   hands-free microphone over SCO, while `VOICE_RECOGNITION` typically stays on the phone's own
   mic - which may be in a pocket or a mount facing away. If this is the cause, no amount of gain
   fixes it and the routing has to be chosen explicitly.

**Establish which before tuning anything.** Was Bluetooth connected in the Jeep at the time? That
single fact separates the two, and Kevin can answer it.

### Why this is blocked on ticket 08 rather than a fix of its own

[The wake word cannot tell silence from a quiet room](08-silenced-not-quiet.md) already concluded
that the only real option is to retire `SpeechService` and drive `Recognizer.acceptWaveForm()` from
our own capture loop. **That refactor is the fix for this ticket too**, and for the same reason:
owning the `AudioRecord` is what makes the source, the processing, the routing, and the silence
signal all choosable instead of inherited.

It also hands [How many false triggers is too many](07-false-triggers.md) the audio levels it needs.
One piece of work, three tickets - which is a much better trade than it looked like when ticket 08
was only about detecting silence.

## Answer

**Bluetooth ruled out by Kevin** ("no not bluetooth. just phone only"), which kills the routing
hypothesis and leaves the processing one. So the fix is to open a different microphone, and the only
way to do that was to stop letting Vosk own the record - the same refactor
[The wake word cannot tell silence from a quiet room](08-silenced-not-quiet.md) needed.

Built 2026-08-20. The engine now opens:

- `AudioSource.VOICE_COMMUNICATION` instead of Vosk's hardcoded `VOICE_RECOGNITION` - the same
  source the live session uses, which is the one that hears Kevin fine at road speed
- `setPrivacySensitive(true)` on API 30+, matching the live session
- hardware `AcousticEchoCanceler`, `NoiseSuppressor` and `AutomaticGainControl` where available.
  **AGC is deliberately in this set although the live session does not use it**: a live turn is
  speech aimed at the phone, while a wake word has to catch an aside from across a noisy cabin.

Confirmed on the A25 in logcat, which now reports its own capture the way `GeminiLiveSession` always
did - the asymmetry that made this bug take a comparison to find:

```
WakeWordEngine: AudioRecord opened: source=VOICE_COMMUNICATION sessionId=29721 effects=2 state=1
```

`effects=2`, not 3 - one of the three is unavailable on this device. Which one is unestablished and
does not block anything.

### NOT verified, and only a drive can

The change is `on-device` in that the right microphone demonstrably opens. **Whether it actually
triggers in the Jeep at road speed is untested**, and that is the only test that matters for this
ticket. If it still fails there, the next suspect is gain rather than source, and `peakLevel` now
exists to say whether the microphone heard anything at all.
