---
map: wake-word
ticket: "08"
title: "The wake word cannot tell silence from a quiet room"
type: task
status: open
status-detail: "Surfaced by ticket 01's research, 2026-08-20. Confirmed against primary docs and against shipped code: GeminiLiveSession already detects silencing, WakeWordEngine and AmbientListener do not."
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
