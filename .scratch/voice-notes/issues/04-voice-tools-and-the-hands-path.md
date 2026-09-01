---
map: voice-notes
ticket: "04"
title: "Two voice tools, and the screen that does the same thing"
type: build
status: open
status-detail: ""
blockers: ["01", "02", "03"]
blocked-by: ["[[01-the-recorder-and-the-mic]]", "[[02-the-store]]", "[[03-transcribe-and-summarize]]"]
open-blockers: 3
ready: false
tags: [ticket]
---
# Two voice tools, and the screen that does the same thing

## What to build

**`voice/VoiceNoteController.kt` first.** ADR 0035 does not mean two implementations - both paths
call this one controller, because two implementations of one capability drift into disagreeing.

**Voice, in `service/LiveToolbox.kt`:**

- `start_voice_note` - optional title hint, optional `kind` (solo / meeting). Refuses in words if one
  is already running or the mic is held.
- `stop_voice_note` - stops, kicks off ticket 03, and **says the recording is saved and being
  transcribed, never that the note is ready.** The outcome-verb rule: transcription has not happened
  at the moment this returns.
- `read_voice_note` and a query tool - read a summary back.

Each needs an entry in `tools/voice_guide_copy.py`'s `COPY` dict or `tools/voice_guide.py` exits
non-zero and names it. Write human copy; do not paste the Kotlin `description`.

**Hands, `ui/voicenotes/VoiceNotesScreen.kt`:** a record button with elapsed time and an obvious
stop, a list of notes, a detail view with summary and full transcript, playback of the audio, rename,
and delete. Delete confirms in words that audio and transcript go with it.

**Every surface renders the summary as LLM-derived, in words, never by colour or a glyph alone.** A
rule explainer may collapse behind a `HelpRow`; a derived or unverified line never may. An
interrupted recording says so on the list row, not only in the detail.

## Verification

- `python tools/voice_guide.py` exits zero and the new tools appear in `docs/voice.html`.
- Compose test: the derived-summary wording is present on both the list row and the detail.
- Compose test: an interrupted note renders as interrupted.
- `./gradlew testDebugUnitTest`, totals read from the JUnit XML under `app/build/test-results/`.
- **On the phone (owed):** record by voice and stop by hand, then record by hand and stop by voice.
  Both crossings must work, or the two paths have already drifted.
