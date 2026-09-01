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

**Inline base64 is not safe at this size.** A 45-minute recording at ticket 01's bitrate clears 10 MB
and the request cap is not far above it. Use the Files API: upload the `.m4a`, poll until the file is
active, then one `generateContent` referencing the file URI. **Establish the current size limits and
server-side retention from the API docs rather than assuming them** - fire a `/research` subagent for
that and write the numbers into the resolution. Retention on Google's side is a fact Kevin should
have in front of him, not a guess.

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
