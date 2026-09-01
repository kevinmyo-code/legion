---
map: voice-notes
title: "Map: Voice notes - record a room, keep what was said"
charted: 2026-09-01
charted-by: "Kevin + Opus"
effort: "`.scratch/voice-notes/`"
tickets: 4
open: 4
status: open
tags: [map]
---
# Map: Voice notes - record a room, keep what was said

## Destination

**Kevin turns it on before a meeting or a solo thought, talks, turns it off, and LEGION leaves
behind a note holding the audio, the verbatim transcript and a summary - reachable by voice and by
hand, synced to the backend like any other record.**

Execution is in scope. Every decision this map needed was made on 2026-09-01 before it was charted,
so all four tickets are `build`, not `grilling`. This is a route with no fog on it, written down
because the work is bigger than one session and because §12 requires a decision that authorises code
to leave a build ticket behind.

## Notes

**Domain:** LEGION, Android phone app (Kotlin, Compose, Room). Read `CLAUDE.md` for rules.

**Where this came from.** Kevin, 2026-09-01: *"i want to add a voice note taking capability. either
in meetings, i turn it on > voice records transcripts and makes a summary and saved it to backend as
a note. either group meetings, or solo thoughts."*

### The three rulings this map stands on

All three from Kevin, 2026-09-01, in one pass. `memory/library/decisions.md` and
`docs/adr/0041-a-recording-kevin-starts-is-first-party.md` hold them in full.

| Fork | Ruling |
|---|---|
| What transcribes it | **Record to a file, batch upload.** Not the Live socket, not local Vosk |
| Does §7 read-through bind a recording | **No.** *"drop 7 entirely. we can keep all transcripts"* - carved out for recordings, mail path untouched |
| What is owed the room | **Nothing in-app.** *"they will know, i'll tell tem"* |

### What exists today - traced 2026-09-01, not remembered

| Piece | State |
|---|---|
| `service/AriaForegroundService.kt` | Already declares `foregroundServiceType="connectedDevice\|dataSync\|microphone"` and holds `FOREGROUND_SERVICE_MICROPHONE`. A long capture needs no new service |
| `service/MicArbiter.kt` | Three fixed claimant ranks (`LIVE_TURN`, `RING_LISTENING`, `WAKE_WORD`), refuse-not-queue. **Recording needs a fourth** |
| Audio to disk | **Nothing.** No `MediaRecorder()` is constructed anywhere - both existing hits are constant-only imports for `AudioSource.VOICE_COMMUNICATION` |
| `ai/SubAgent.kt` | One-shot BYO-key Gemini helper. `ask` takes `imageBytes`/`imageMimeType`. **No audio parameter** |
| `ai/GeminiKeyProvider.kt` | BYO key first, `BuildConfig` fallback. Same key the Live session uses |
| Notes storage | Came OFF the engine 2026-08-27. A note is a typed Room table plus a typed Supabase table now, **not** a `RecordType` seeder |
| `service/AmbientListener.kt` | **Does not exist.** Retired 2026-08-21. `.scratch/ambient-listening/map.md` still describes it as live at 279 lines and is wrong |

### Standing preferences for this effort

- **Kevin is at the abstraction layer.** Implementation is decided without asking him.
- **ADR 0035 binds:** every voice tool here ships with a hands path calling the same controller.
- **The anchor chain is the point** (ADR 0041). Summary anchored by transcript, transcript anchored
  by audio, all three deleted together. A summary that outlives its transcript is the defect.
- **Nothing numeric heard in a recording is a fact.** A figure spoken in a meeting reaches the
  ledger only through the ledger's own ingestion path, never through a note.

## Decisions so far

<!-- one line per resolved ticket -->

## Not yet specified

- **Speaker labels.** The batch-upload path makes diarization possible where local Vosk did not.
  Whether the summary names who said what, and how wrong it is allowed to be, is unasked.
- **How long audio is kept.** The chain argues for keeping it forever; a phone's storage argues
  otherwise. Nobody has picked a ceiling.
- **Recording while a call is in progress.** `TelephonyController` and `CallAudioRoute` exist and
  nothing here has considered them.
- **Whether a note can be handed to the assistant as context later** - "what did we decide about X"
  across every recording, rather than reading one back.

## Out of scope

- **Always-on / ambient capture.** Retired 2026-08-21 and not reopened. Every recording here starts
  and stops on a deliberate act.
- **Streaming live transcription during the meeting.** The ruling picked batch upload; a live
  running transcript is a different feature with a different cost.
- **Any in-app consent surface** - indicator, announcement, passenger handling. Ruled out by Kevin
  on 2026-09-01, handled out of band.
