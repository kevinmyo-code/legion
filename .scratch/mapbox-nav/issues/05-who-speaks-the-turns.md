---
map: mapbox-nav
ticket: "05"
title: "Who speaks the turns, and audio beside a live mic"
type: decision
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-sdk-facts]]"]
open-blockers: 0
ready: true
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
