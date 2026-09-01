---
map: voice-notes
ticket: "03"
title: "One upload, a transcript and a summary out"
type: build
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-the-recorder-and-the-mic]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# One upload, a transcript and a summary out

## What to build

`ai/VoiceNoteAgent.kt`, and the audio path `ai/SubAgent.kt` does not have.

`SubAgent.ask` today takes `imageBytes` / `imageMimeType` and nothing else. Add audio the same way it
took images: **an additive parameter, not a second helper class.** The key comes from
`GeminiKeyProvider` exactly as it does now. Nothing here introduces a second key path.

## The facts are established - do not re-derive them

`.scratch/voice-notes/research/gemini-audio-upload.md`, fetched 2026-09-01. Use these; do not fire
another research pass.

| | |
|---|---|
| Model | `gemini-3.7-flash` |
| Inline cap | 20 MB total request, so anything meeting-shaped goes through the Files API |
| Upload | Resumable, two legs. Session URL arrives in the `x-goog-upload-url` RESPONSE HEADER, not the body |
| Polling | `GET /v1beta/files/{id}` until `state` is `ACTIVE`. A `PROCESSING` uri fails at inference |
| MIME | **`audio/m4a`. NOT `audio/mp4`** - `audio/mp4` is not on Google's accepted list |
| Google-side retention | 48 hours, auto-deleted. `DELETE /v1beta/files/{name}` exists - **call it once the transcript is in hand** rather than waiting out the 48 hours |
| Tokens | 32 per second of audio. One hour is 115,200 input tokens |
| Cost | ~$0.13 per recorded hour, a floor not a forecast - thinking tokens bill as output and no typical volume could be established |
| Output cap | 65,536 tokens, ~49k words. One hour of verbatim fits. **Three hours plus will not** |

**Key tier is settled and needs no code (Kevin, 2026-09-01):** *"its a paid key. it will always be a
paid key."* No disclosure screen, no tier detection - there is no API field for it anyway. The
free-tier training exposure is real and documented in the research file; it is not LEGION's problem
to solve.

**Inline base64 is not safe at this size.** A 45-minute recording at ticket 01's bitrate clears 10 MB
and the request cap is not far above it. Use the Files API: upload the `.m4a`, poll until the file is
active, then one `generateContent` referencing the file URI. Sizes and retention are settled in the
table above. **Delete the uploaded file as soon as the transcript is in hand** rather than leaving it
to time out.

**Handle the output cap.** A recording long enough to exceed 65,536 output tokens must chunk or fall
back to summary-plus-segments, and must say in words that the transcript is partial rather than
returning a truncated one that reads as complete.

**One call, `askTyped`, structured output:** `{ title, summary, transcript }`. Not three calls. The
summary must come from the same pass that produced the transcript, or the two disagree and ADR
0041's anchor chain is decorative.

## What it must not do

- **Not assert anything numeric as fact.** The prompt says plainly that figures, dates and names
  heard in a recording are what a speaker said, not what is true, and that they belong to the
  transcript and nowhere else. Nothing here writes a ledger row, a reminder or a goal, however
  clearly someone said it out loud.
- **Not invent.** An inaudible stretch is marked inaudible. A meeting that decided nothing gets a
  summary saying so, not a plausible one. This is the honesty posture at the data layer and it is
  the failure most likely to ship unnoticed, because a fluent summary of a vague meeting looks
  exactly like a good one.
- **Not claim success it did not observe.** A failed upload leaves `transcript` null and the audio on
  disk, retryable, and says in words what did not happen.

## Verification

- Unit test over the prompt's presence, the way `AriaBrainHonestyClauseTest` guards its clause.
- Unit test: a failed call leaves the row retryable with audio intact, and never writes a summary
  against a null transcript.
- **On the phone (owed):** one real 20-minute recording end to end. Record wall-clock time and token
  cost in the resolution. Kevin asked for cost visibility on the retired ambient map and never got
  it.
