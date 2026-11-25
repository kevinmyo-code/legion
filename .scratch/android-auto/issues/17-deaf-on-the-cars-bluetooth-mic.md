---
map: android-auto
ticket: 17
title: "Alfred hears nothing at all through the car's Bluetooth mic"
type: bug
status: built
status-detail: "2026-11-24 - fallback to the phone's mic built and CI green; the trigger has not been shown to fire in the car, and it owes a drive"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Alfred hears nothing at all through the car's Bluetooth mic

## The report

Kevin, from the car, 2026-11-24: with his phone connected to the head unit's Bluetooth, the phone
app did not hear him at all.

## What the phone's own log says

Device log lines only, no words of his. Six conversations over about eighty minutes.

- Every one of them logged `bluetooth mic ok=true` and then `bluetooth mic routed=true`. The
  hands-free link came up each time. "SCO never connects" is ruled out.
- One conversation worked: a run of turns with real speech-to-text, a speech onset each time and
  seconds of voiced audio.
- One fired a speech onset and then reported ZERO voiced milliseconds inside the audio it kept.
- Three opened the route, opened the capture, and then logged nothing further at all: no onset, no
  speech-to-text, no pause, closing on the follow-up window or on an unanswered check-in.

So the capture opens and delivers buffers, and the turn detector never hears speech begin. The same
route worked earlier the same hour, so the hands-free microphone is not simply absent.

## Root cause of the part that is a LEGION bug

Whether that link carries his voice was never checked, and there was no way back.

`CallAudioMode.handsFreeRouted` reads back which device the platform SELECTED for the call route. It
says the selection took; it says nothing about the audio. A hands-free unit whose microphone is
absent, unplugged, muted or gated sends silence down a perfectly routed link. The cascade waited for
that readback, logged it, opened the capture either way, and from then on every turn of the
conversation was deaf with no path back to the phone's own mic.

Second half: the cascade logged no level at all. The deaf-mic watchdog the retired Live loop had
(ticket 15 in `wake-word`, `MicSignal.isDeafMic`) was not carried over when the cascade replaced
Live on 2026-11-15, so the one instrument built for this failure was missing when it happened.

## What was built

- `BluetoothMic.fallBack`, the whole decision as a pure function: on the hands-free mic only, once per
  conversation only, when the capture has stayed at digital silence past `DEAF_MS`.
- The cascade reads one peak per buffer where every buffer passes, publishes it for the screen's mic
  bar, and logs `mic_peak=` about once a second with the route it is on.
- A window that closes having heard no turn now logs `heard_nothing=true` with its route and the
  loudest sample it ever read.
- On a trip: the link goes, the player is rebuilt, the phone's mic opens with the open chime, and he
  is told in words. The conversation, its backend and its prompt stay up.
- A capture is never judged while Alfred is playing: a hands-free unit's own echo cancelling can hold
  its microphone down to nothing while the far end speaks, and that is a working mic.

## Verification

- Failing tests first, then the fix. Pure tests over the decision, wiring tests over the plumbing.
- Android, Checks and Server workflows in GitHub Actions.
- NOT done, and the gap to close: no level from the real failure has ever been measured, so
  `DEAF_MS` and the silence floor are guesses inherited from `MicSignal`, which says so about its
  own. The new `mic_peak=` and `heard_nothing=` lines are what settle it. A capture carrying noise
  but no voice, which one of the six conversations looks like, would not trip the fallback.
- Owed: a drive with the head unit's Bluetooth connected, then a read of the phone's log for
  `heard_nothing=`, `mic_peak=` and `hands-free mic deaf`.

## Until it ships

Assistant settings, "Use the car's or headset's microphone" off. The phone's own mic then listens, as
it did before the setting existed. The setting defaults ON, which is why this reached him without his
choosing it.
