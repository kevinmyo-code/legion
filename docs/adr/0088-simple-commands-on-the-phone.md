---
status: accepted
decided: 2026-11-10
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-11-10 - Simple commands on the phone's own recogniser (Kevin)]]"
tags: [adr]
---

# 88. Simple commands are done on the phone; everything else goes to Alfred unchanged

## Standing

**A closed set of simple commands is recognised and done on the phone, with no model:** timers,
alarms with a clear time of day, pause/resume/next/previous, volume, dismissal ("that's all",
"go to sleep", "quiet"), opening a named screen, clearing ticked lines on a named list. The first
turn of a tapped or woken conversation is held on the phone (`service/turn/HeldTurnSink.kt`),
read by Vosk against the command vocabulary (`service/local/LocalCommandGate.kt`) and matched by
`service/local/LocalCommandMatcher.kt`. A hit runs the same tool Alfred would call (ADR 0035), says
the tool's own words with on-device speech and ends the conversation. **A miss replays the held
turn to Live whole**, so Kevin never repeats himself. Behind the "Simple commands on the phone"
switch (Assistant settings), off until it has run on the phone; manual activity only (not over car
Bluetooth, where the server decides turns).

## Context

Kevin, 2026-11-10: *"we offload as much as we can to the phones own speech recognizer."* Each
Live generation re-bills the whole setup; a timer or a pause does not need a model.

## Decision

- The matcher reads words, not templates; walks the tokens once; and misses on any word it does
  not account for, any ambiguity, an unknown word or a confidence under 0.8. A confident wrong
  answer is the failure it is built against.
- The local path never asks a model and never claims more than the tool's own result.

## Consequences

- A hit costs no Live generation; a miss costs the hold (the recogniser's run, a fraction of a
  second) on top of today's turn.
- The vocabulary is a fixed list in code; a new command is a matcher change with its tests.
