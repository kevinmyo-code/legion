---
map: wake-word
ticket: "01"
title: "Does a foreground service still get the microphone with the screen off?"
type: research
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Does a foreground service still get the microphone with the screen off?

## Question

`AriaForegroundService` runs continuously, and `WakeWordEngine` opens a Vosk `SpeechService` on its
own thread. That worked on an AOSP head unit that never slept. **Nobody has established that it
works on a Samsung A25 running One UI with the screen off.**

Establish, from primary sources (Android developer docs, the `foregroundServiceType` reference, and
Samsung's own battery documentation where it exists):

1. What `foregroundServiceType` a continuously-recording service must declare on the target SDK, and
   what LEGION currently declares in `AndroidManifest.xml`. If they differ, that is a finding.
2. Whether Doze, App Standby, or One UI's own "Deep sleeping apps" / adaptive battery can stop a
   microphone-holding foreground service, and under what conditions.
3. Whether anything revokes microphone access while the screen is off, or on the privacy-indicator
   path introduced in Android 12+.
4. What a phone call, or another app taking the mic, does to a held `SpeechService` - does it error,
   go silent, or return empty audio? **Going silent with no error is the failure mode LEGION has
   been bitten by before** (`.scratch/android-auto/issues/15-the-live-session-can-be-silenced.md`).

**Do not answer from memory.** Cite the doc. A wrong answer here sends the whole map down a path
that cannot work.
