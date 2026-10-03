---
map: wake-word
ticket: 16
title: "How long a conversation stays open after the assistant answers"
type: decision
status: resolved
status-detail: "Kevin, 2026-10-03: open with an 8 second follow-up window; the close phrase still works. Build is ticket 17."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# How long a conversation stays open after the assistant answers

## Question

**Kevin, 2026-10-03:** *"im still struggling with if the wake word should open only 1 turn and close
or keep it open. i did this previously by keeping it open, and having a close phrase to close the
convo."*

As built on 2026-10-03, a hands-free conversation stays open until the model calls
`end_conversation` (the close phrase). Only speak-only sessions have an idle timeout
(`GeminiLiveSession.IDLE_TIMEOUT_MS`). An open conversation that nobody closes keeps the mic live,
transcribes whatever is in the room (the A25 transcribed background audio as "Pío pío" the same day)
and bills Gemini context on every turn.

| | One turn, then close | Open until the close phrase | Open with a silence timeout |
|---|---|---|---|
| Follow-ups | Wake word again every time | Natural | Natural |
| Forgetting to close | n/a | Mic stays open, overhears the room, every turn billed | Closes by itself |
| Cost | Lowest | Highest | Low |

## Resolution

**Kevin, 2026-10-03:** *"8 sec sounds right."* (after choosing the silence-timeout shape, modelled on
Siri's and Google's continued-conversation mode).

- After the assistant finishes speaking, the mic stays open for **8 seconds**.
- Speech inside the window continues the conversation; the window re-arms after each answer.
- Silence for the whole window closes the conversation quietly with a short tone, in words on the
  strip ("Conversation closed"), never by a silent disappearance.
- The close phrase (`end_conversation`) still works and closes immediately after the sign-off.

Build: [[17-follow-up-window]].
