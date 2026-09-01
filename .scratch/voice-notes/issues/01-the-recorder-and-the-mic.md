---
map: voice-notes
ticket: "01"
title: "The recorder, and the fourth claim on the microphone"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# The recorder, and the fourth claim on the microphone

## What to build

`voice/VoiceNoteRecorder.kt` - the first audio-to-disk path in the codebase.

- `MediaRecorder`, AAC in an MPEG-4 container, mono, 16 kHz sample rate, around 32 kbps. An hour
  lands near 14 MB, which matters to ticket 03's upload.
- Output to `context.cacheDir/voicenotes/<id>.m4a`. Not external storage, not `FileProvider`.
- Start and stop are explicit. No timer, no VAD, no auto-stop on silence. A recording ends because
  someone ended it or because the process died.
- Survive process death honestly: an `.m4a` on disk with no matching row is an orphan and is deleted
  on next start. A row with `endedAt == null` on app start is marked interrupted, keeps whatever
  audio was flushed, and is never silently presented as complete.

## The mic

`service/MicArbiter.kt` has three ranked claimants and refuses rather than queues. Add a fourth,
`VOICE_NOTE`, and rule its precedence explicitly rather than by where it lands in an enum:

- **Yields to `LIVE_TURN`?** No. A recording in progress is not interrupted by the assistant
  deciding to listen. Starting a Live turn while recording fails with a worded refusal.
- **Preempts `WAKE_WORD`?** Yes. The wake word already yields to everything.
- **`RING_LISTENING`?** A call arriving stops the recording, marks it interrupted, and keeps the
  audio. Losing the call to protect the recording is the wrong trade.

Whatever is decided here is decided in the arbiter with a test, not implied by ordering.

## Verification

- Unit test: start, stop, file exists and is non-empty.
- Unit test: an orphan `.m4a` with no row is deleted on next start.
- Unit test: a row with `endedAt == null` reads as interrupted, not complete.
- Unit tests over the arbiter for each of the four precedence rules above.
- **On the phone (owed, not optional):** record 10 minutes with the screen off, confirm the file
  survives, confirm `AriaForegroundService` was never killed. `foregroundServiceType` already
  includes `microphone`; confirm no new manifest change is needed rather than assuming it.
