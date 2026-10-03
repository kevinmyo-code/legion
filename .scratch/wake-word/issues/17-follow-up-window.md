---
map: wake-word
ticket: 17
title: "An 8 second follow-up window closes a conversation nobody continues"
type: build
status: open
status-detail: ""
blockers: ["16"]
blocked-by: ["[[16-how-long-a-conversation-stays-open]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# An 8 second follow-up window closes a conversation nobody continues

Ruled by [[16-how-long-a-conversation-stays-open]].

## Behaviour

- Applies to every conversation (wake word and tap to talk).
- The window starts when the assistant's turn completes and the mic reopens (turnComplete in
  conversation mode). It is cancelled the moment the user starts speaking (input transcript or VAD
  start), and re-armed after the next answer.
- A tool call that is still running is not silence: the window starts only after the turn that
  follows it completes.
- On expiry: close the session the same way a deliberate stop does (`"stopped"`, so no error notice
  and no resume handle carried), play the existing short tone, and show "Conversation closed" on the
  strip in words.
- `end_conversation` (the close phrase) keeps working as today.
- 8 seconds is one named constant with the ticket cited beside it.

## Verification

- Unit: a pure decision function (window armed / cancelled / expired) over the event sequence.
- On the A25: ask one question, say nothing, the conversation closes about 8 s after the answer; ask
  a follow-up inside the window, it continues; the close phrase still closes.
