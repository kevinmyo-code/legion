---
map: wake-word
ticket: "08"
title: "The wake word cannot tell silence from a quiet room"
type: task
status: open
status-detail: "BLOCKED ON A DECISION 2026-08-20. The ticket claimed the fix was to apply GeminiLiveSession's existing pattern. That is not possible: Vosk SpeechService owns its AudioRecord privately with no getter, so there is no session id to match on. Needs Kevin's call between a real refactor and a weaker signal."
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# The wake word cannot tell silence from a quiet room

## Question

Nothing to decide. This is a defect in shipped code, found by
[Does a foreground service still get the microphone with the screen off?](01-mic-under-doze.md).

The platform is explicit: **when another app acquires the audio input, the previously capturing app
keeps running and receives silence.** No exception, no error, no callback on the recognizer. A phone
call always wins. The device-wide microphone toggle does the same thing.

`WakeWordEngine` and `AmbientListener` wrap Vosk's `SpeechService` with `onError` and `onTimeout`
only, and **neither fires on silencing**. So a wake word that has been silenced by a call, by another
recorder, or by the privacy toggle looks exactly like a room where nobody said anything. Kevin would
have no way to tell the difference, and the natural conclusion - "the wake word does not work" - is
one he could reach after it worked perfectly.

**The fix already exists in this codebase.** `GeminiLiveSession.kt:363-364` and `:1237-1240`, with
`CompanionPhase.kt:58`, register an `AudioManager.AudioRecordingCallback` and read
`AudioRecordingConfiguration.isClientSilenced()`. That is the pattern; this ticket applies it to the
two Vosk engines.

The work:

1. Register the callback **before** capture starts - registering after is too late to see the state.
2. Surface the silenced state rather than swallowing it. At minimum the wake word must stop claiming
   to be listening when it is not; `CLAUDE.md` sec 7's outcome-verb rule is the same principle
   applied to speech, and a Settings row reading "On - say hey <name>" while the mic is silenced is
   the same class of lie.
3. Decide what recovery looks like: re-acquire when the silencing ends, and prove it does. A phone
   call is the easy case to reproduce.
4. A test that pins the behaviour, since this is the second time this failure class has appeared -
   `.scratch/android-auto/issues/15-the-live-session-can-be-silenced.md` is the first.

**API 29+ only.** LEGION's `minSdk` is 24; establish what the fallback is on older devices, or state
plainly that there is none and the failure stays invisible there.

## Amendment 2026-08-20: the stated fix does not work

This ticket said *"the fix already exists in this codebase... this ticket applies it to the two Vosk
engines."* **That is wrong, and it was wrong when written.**

`GeminiLiveSession` matches its own recording by session id:

```kotlin
configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId }
```

It can do that because it constructs its own `AudioRecord`. **The Vosk engines do not have one.**
Read in `vosk-android-0.3.47-sources.jar`, `SpeechService.java:39`:

```java
private final AudioRecord recorder;
```

Private, final, **no getter**. The class doc says outright that the service holds the `AudioRecord`.
So there is no session id to match, and the existing pattern cannot be applied as written.

Matching by elimination does not save it: the platform documents
`getActiveRecordingConfigurations()` as *"a general view of all active recordings on the device"*,
not a per-app view, so "any silenced config means we are silenced" would false-positive on another
app entirely.

### The fork

**A. Drive `Recognizer` from our own capture loop and retire `SpeechService`.** Vosk's `Recognizer`
takes `acceptWaveForm(byte[], int)` directly, so `SpeechService` is a convenience wrapper, not a
requirement. Owning the `AudioRecord` gives the session id and therefore the exact
`isClientSilenced` signal `GeminiLiveSession` already uses, plus raw levels for free - which is also
most of what [How many false triggers is too many](07-false-triggers.md) will want. Roughly 60-80
lines mirroring `GeminiLiveSession`'s `micLoop`. **The correct fix, and a real refactor of an engine
that started working hours ago.**

**B. Reflection into the private field.** Works today, breaks silently on any library bump. Rejected
- a fragile detector for a silent failure is its own silent failure.

**C. A heuristic on partial results.** Cannot work: Vosk emits partials only on speech, so a
genuinely quiet room and a silenced microphone produce identical output. That identity IS this
ticket.

**A is the only real option**, and the cost is honest refactor risk rather than a hidden one.

## Amendment 2026-08-20 (second): this is now the linchpin, not a nicety

Field data changed what this ticket is worth. Kevin used the working wake word for the first time and
it **did not activate at all in the Jeep**, while working fine outside it -
[Deaf in the Jeep, fine outside it](12-deaf-in-the-jeep.md).

The cause is the same sentence as this ticket's: **Vosk's `SpeechService` owns the `AudioRecord`.**
It hardcodes `AudioSource.VOICE_RECOGNITION` with no way to change it, while the live session that
hears him fine in the car uses `VOICE_COMMUNICATION` with noise suppression and gain.

So option A - our own capture loop feeding `Recognizer.acceptWaveForm()` - now buys three things at
once:

1. the silence signal this ticket was opened for,
2. **the ability to choose the microphone source and routing**, which is the car fix,
3. raw levels, which is most of what ticket 07 needs to measure false triggers.

It was a defensible deferral when it bought only the first. It is the obvious next move now that it
buys all three, and the two other tickets are blocked behind it.
