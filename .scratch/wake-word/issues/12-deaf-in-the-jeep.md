---
map: wake-word
ticket: "12"
title: "Deaf in the Jeep, fine outside it"
type: task
status: open
status-detail: "Field data from Kevin, 2026-08-20, first real-world use: works outside, did not activate at all in the Jeep. Root cause traced to Vosk's hardcoded audio source; fix is ticket 08's refactor."
blockers: ["08"]
blocked-by: ["[[08-silenced-not-quiet]]"]
open-blockers: 1
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
