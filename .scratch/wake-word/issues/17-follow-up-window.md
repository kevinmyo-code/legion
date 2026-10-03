---
map: wake-word
ticket: 17
title: "An 8 second follow-up window closes a conversation nobody continues"
type: build
status: built
status-detail: "Built 2026-10-03 on feat/follow-up-window; suite green; owes a run on the A25 (8 s close, follow-up continues, close phrase, tone audible)"
blockers: ["16"]
blocked-by: ["[[16-how-long-a-conversation-stays-open]]"]
open-blockers: 0
ready: false
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

## Built (2026-10-03)

- `service/FollowUpWindow.kt`: pure fold (turn complete / mic opened / user speech / tool call /
  elapsed) -> arm / cancel / close; `WINDOW_MS = 8_000` cites this ticket and 16.
- `GeminiLiveSession`: reuses `idleJob` (no second timer). Clock starts at `MicOpened`, not
  turnComplete, so the assistant's playback tail does not eat the window. Cancel signal: first
  `inputTranscription` text (earliest the client sees; no explicit VAD start message is handled).
  Tool calls counted in flight; expiry with one running does not close. Expiry emits
  `LiveEvent.FollowUpExpired` then `closeSession("stopped")`.
- `LiveSessionController`: on `FollowUpExpired` plays a ToneGenerator ACK (no earcon existed) and
  shows the notice "Conversation closed".
- Open risk for the A25: with start sensitivity LOW the transcript can lag speech onset, so speech
  begun in the last second of the window could be cut off.
