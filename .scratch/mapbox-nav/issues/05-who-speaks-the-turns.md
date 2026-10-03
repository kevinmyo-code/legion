---
map: mapbox-nav
ticket: "05"
title: "Who speaks the turns, and audio beside a live mic"
type: decision
status: resolved
status-detail: "Kevin: C, LEGION speaks Mapbox turn text"
blockers: ["01"]
blocked-by: ["[[01-sdk-facts]]"]
open-blockers: 0
ready: false
tags: [ticket]
---

# Who speaks the turns, and audio beside a live mic

## Question

Mapbox has its own spoken turn instructions. The assistant has a voice. Who says "turn left in 300
metres"?

- Mapbox's stock voice: on time, separate from the persona, but a second voice in the car.
- The assistant's voice: one voice, but Gemini Live latency on a time-critical cue, and token cost
  per instruction.
- Mapbox voice for turns, with the assistant answering questions about the route.

And the audio: what happens when a turn cue fires while the user is mid-sentence to the assistant,
or the assistant is mid-reply? Ducking, queueing, `MicArbiter`. Does the cue's audio leak into the
open mic and get transcribed as speech?

## Answer (2026-10-03)

**Kevin: C.** Mapbox decides WHAT and WHEN; LEGION's own speech path says it. The assistant never
speaks a turn instruction.

- **Source:** `VoiceInstructionsObserver` hands over `announcement()` / `ssmlAnnouncement()` at the
  moment Mapbox times it (research 01 section 4). Mapbox's own voice player is never wired, so there
  is no second, uncoordinated voice.
- **Voice:** one steady TTS voice, not the persona. A turn cue is an instrument reading, not
  conversation. Which engine (on-device `TextToSpeech` vs Mapbox's speech API) is a build detail for
  ticket 11: on-device by default because it works in dead zones.
- **A cue wins.** If the assistant is mid-reply, its playback pauses for the cue and resumes after.
  The mic is gated for the cue's duration through `MicArbiter`, so the cue is never transcribed as
  the user speaking.
- **The assistant answers ABOUT the route** ("how long left", "what's the next turn") from route
  progress via a tool (ticket 04). It reads the SDK's state; it never paraphrases a cue live.
- **Mute** silences cues only, never the assistant, and is a hands control on the nav screen too.

Owed on the phone (ticket 12): a cue firing while Kevin is mid-sentence and while the assistant is
mid-reply.
