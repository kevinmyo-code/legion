---
map: matrix-open
ticket: "05"
title: "Build: Matrix notes and voice notes"
type: build
status: built
status-detail: "Built 2026-10-24, suite green. Owed on the A25: LEGION > Notes lists notes, write one, open it, edit its text, tick it, delete it with the second tap; LEGION > Voice notes: Record shows Recording and a running clock, Stop says it is saved and being transcribed (not ready), the recording appears, opens with its AI-labelled summary and transcript, Play audio plays, Rename works, Delete asks twice; deny the microphone and see what the refusal says."
blockers: []
tags: [ticket]
---

# Build: Matrix notes and voice notes

Notes list, read, write, edit; voice notes list, record, play, read transcript and summary. Same controllers as the Soft screens.

**Style (Kevin, 2026-10-24): text-only, like Matrix HOME.** Green monospace on black from
`ui/dumb/MatrixScheme.kt`, rows you tap, no cards, icons, images or charts, no rain. A new screen, not
a recolour of the Soft one. Reached from Matrix HOME while the mode is on; the Soft screen is unchanged
when it is off. Every action also says its failure in words.

Verify: ViewModel / UiState tests for the screen's states (loading, empty, unreadable said apart from
empty, error). On the A25: open each from Matrix HOME and do one write.

## What was decided while building

Orchestrator decisions (2026-10-24), as in ticket 03; the LEGION index lists Notes and Voice notes.

- **What "Notes" is.** LEGION has no free-text notes store. The notes aspect is the reminder items
  `NotesController` owns (the Notes screen was deleted in one-today ticket 10; its items live on as
  reminders). `MatrixNotesScreen` lists those, undated and dated, open first: read one (text, date,
  repeats, place trigger), write one, edit its text, tick, delete. It does not set times, repeats or
  place triggers; the Soft calendar does. A repeating note says in words it cannot be ticked here.
- **One implementation.** `AndroidMatrixNotesSource` calls `NotesController.addItem`, `renameItem`,
  `tick`/`untick`, `removeItem`; `AndroidMatrixVoiceNotesSource` calls `VoiceNoteController.start`,
  `stop`, `rename`, `delete`, `retryTranscription`, `listNotes`. A rename's sentence goes through the Soft
  screen's own `renameNotice`, and a refused start shows the controller's own sentence.
- **Playback** is the same `MediaPlayer` calls the Soft detail screen makes, in the Matrix screen; a
  failure to play is said in words. The recorder has no explicit microphone-permission check (same as the Soft screen), so a denied
  microphone is expected to surface as its "Couldn't start recording: ..." refusal; that is reasoned,
  not seen, and is on the A25 list.
- **Delete** (notes and recordings) asks once more before deleting. Stop says "saved and being
  transcribed", never "ready" (the outcome-verb rule).
- **Not built:** voice-note kind choice (a Matrix recording is a Solo one, as the Soft Record button
  starts), a meeting recording from Matrix.
