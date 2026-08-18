---
map: android-auto
ticket: 15
title: The live session can be silenced with no error
type: task
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# The live session can be silenced with no error

## Question

Ticket 04 found this while answering something else, and it is not an Android Auto problem - it is a
defect in the shipped live session that happens to be worst in the car.

`GeminiLiveSession.kt:888` opens its capture with `MediaRecorder.AudioSource.VOICE_RECOGNITION`.
That source is **not** privacy-sensitive, so when another app takes a privacy-sensitive capture -
the Android Auto Assistant being the obvious one - the platform hands LEGION **zeroes**. No
exception, no callback, no state change. LEGION carries on with an open socket, a live session, and
silence on the wire. It is the fifth silent-unreachable shape, inside the one component the whole
product depends on.

`MODIFY_AUDIO_SETTINGS` is also absent from `AndroidManifest.xml`, and **both** microphone routing
paths need it.

Do:

1. **Prove it first.** Start a live session, invoke "Hey Google" over the top, and observe what the
   session receives. `traced` from a research agent's read is not `tested`, and `memory/MEMORY.md` is
   explicit that a relayed claim is not a verification. If it does not reproduce, say so and stop.
2. Add `MODIFY_AUDIO_SETTINGS` to the manifest.
3. Switch the capture to `VOICE_COMMUNICATION` and/or call `setPrivacySensitive(true)`. Note that
   `VOICE_COMMUNICATION` also brings the platform's echo cancellation, which the car surface wants
   anyway.
4. **Make the silencing detectable regardless.** Register an
   `AudioManager.AudioRecordingCallback` and read `AudioRecordingConfiguration.isClientSilenced()`.
   Whatever source is chosen, LEGION should be able to say "I am being silenced" instead of appearing
   to listen. Surface it in the UI, not in logcat - the A17k filters app logs.
5. Off-ticket but carry it: `AriaForegroundService` declares the `dataSync` foreground service type,
   which Android 15 caps at **6 hours per 24**. Check whether that bites a long drive, and whether
   the declared type is even the right one now.

This is a phone-side defect with or without the car. If it reproduces, it should not wait for the
rest of this map.

## Verification 2026-08-16 - PARTIALLY BUILT

Checked against the tree during the all-effort sweep. Kept OPEN: two items are unmet. All `traced`.

**Built:**
- `MODIFY_AUDIO_SETTINGS` in the manifest (`AndroidManifest.xml:28`).
- **The capture source was switched**: `GeminiLiveSession.kt:934` uses
  `MediaRecorder.AudioSource.VOICE_COMMUNICATION`, plus `setPrivacySensitive(true)` on API 30+
  (`:937-939`). `VOICE_RECOGNITION` survives only in the explanatory comment (`:909`). **This is on
  the core voice path, not a car-only branch.**
- **Silencing is detectable**: an `AudioManager.AudioRecordingCallback` registered at `:980`
  (API 29+ guard at `:961`) reads `isClientSilenced` for the matching session id, flips `_isSilenced`
  (`:274-275`), logs `MIC_SILENCED` to `CarProbeLog` (`:974`), explicitly logs the sub-API-29 gap
  (`:982-986`), and resets on teardown (`:1074-1075`).

**Not built:**
- **Item 1, the device experiment, has never run.** Still `traced`, never `tested`; nothing has
  plugged into a head unit.
- **Item 5 is untouched**: the manifest still declares `connectedDevice|dataSync|microphone`
  (`:150-154`) with no reference anywhere to Android 15's six-hour `dataSync` cap.

**And the signal only reaches a debug screen.** `isSilenced` has exactly one production consumer -
`LiveSessionController.kt:118-124`, which mirrors it into `CarProbeLog`. No `AssistantStrip`,
notification or overlay reads it. So the ticket's own goal, "LEGION should be able to say I am being
silenced", is satisfied only on a Settings diagnostic page, not to the driver.
