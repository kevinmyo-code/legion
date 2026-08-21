---
map: wake-word
ticket: "01"
title: "Does a foreground service still get the microphone with the screen off?"
type: research
status: resolved
status-detail: "Resolved 2026-08-20. Manifest already compliant. Doze does not stop a running FGS (reasoned from an absence, not an explicit exemption). Samsung deep sleep vs a mic FGS: NOT ESTABLISHED. Silencing confirmed: another app taking the mic yields silence with no error, and neither engine detects it - ticket 08."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
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

## Answer

Resolved 2026-08-20 by a research subagent against primary sources. Verification tags are the
agent's own and are relayed unchanged, per CLAUDE.md sec 8.

### 1. Foreground service type - LEGION already complies. No finding. `sourced` + `traced`

Android 14 and 15 require `foregroundServiceType="microphone"`, the
`FOREGROUND_SERVICE_MICROPHONE` permission, a runtime `RECORD_AUDIO` grant, and
`FOREGROUND_SERVICE_TYPE_MICROPHONE` passed to `startForeground()`. A mismatch throws
`SecurityException` at `startForeground()` time.

`AndroidManifest.xml:157` already declares `connectedDevice|dataSync|microphone`, `:14` holds
`RECORD_AUDIO` and `:18` holds `FOREGROUND_SERVICE_MICROPHONE`, and
`AriaForegroundService.startForegroundCompat()` (`:894-933`) gates the microphone bit on the runtime
grant and retains it across later calls. **The map expected a finding here and there is none.**
Android 15 adds no microphone-specific timeout - those hit `dataSync` and `mediaProcessing`.

One live constraint worth carrying forward: a `microphone` FGS **cannot be started from the
background or from `BOOT_COMPLETED`**. `BootReceiver.kt:36` shows this is already understood.

### 2. Doze, App Standby, One UI deep sleep - mostly fine, one genuine unknown

Doze restricts network, wake locks, alarms, Wi-Fi scans, sync adapters and JobScheduler, and a
running foreground service keeps the app out of App Standby entirely. **But the conclusion that Doze
never stops a running FGS is `reasoned` from an ABSENCE in the restriction list, not from an explicit
exemption.** Do not report it as sourced.

**Samsung's "Deep sleeping apps" versus a running microphone FGS is NOT ESTABLISHED.** Samsung
documents that deep-sleeping apps "will never run in the background", and publishes nothing about
how that interacts with a live mic FGS. On a Samsung A25 that is a real risk, and it is one of the
things the battery-measurement ticket will incidentally prove or disprove by simply surviving.

### 3. Screen off - not the risk. The device mic toggle is

Nothing revokes the microphone on screen-off. The real gate is the device-wide privacy toggle, and
when it is off **the app receives silent audio** - no exception, no error. Android 9+ blocks
background mic capture outright, and the documented remedy is exactly LEGION's shape: use a
foreground service.

### 4. The silent failure is REAL, and both engines are blind to it. `sourced`

Verbatim from the platform docs: *"In most cases, if a new app acquires the audio input, the
previously capturing app continues to run, but receives silence."* A phone call always wins. Two
ordinary apps never capture at once; foreground beats background; last-started wins ties.

The only signal is `AudioManager.AudioRecordingCallback` into
`AudioRecordingConfiguration.isClientSilenced()` (API 29+), and it must be registered **before**
capture starts.

**`GeminiLiveSession.kt:363-364,1237-1240` and `CompanionPhase.kt:58` already do exactly this dance.
`WakeWordEngine` and `AmbientListener` do not** - they wrap Vosk's `SpeechService` with only
`onError` and `onTimeout`, neither of which fires on silencing. A silenced wake word is
indistinguishable from a quiet room, and the driver would have no way to tell.

That is the ticket's own named fear, confirmed, in shipped code. Filed as
[The wake word cannot tell silence from a quiet room](08-silenced-not-quiet.md).
