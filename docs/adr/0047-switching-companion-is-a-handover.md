---
status: accepted
decided: 2026-09-10
decided-by: Kevin
source: "[[decisions#2026-09-10 - Kratos, and handing the conversation to another companion by voice]]"
tags: [adr]
---

# 47. Switching companion is a socket rebuild and a spoken handover

## Standing

**Changing the active companion tears the Live socket down and opens a new one, and the conversation
so far is not carried across.** The outgoing companion says one handover line, the incoming one
greets in its own voice, and it is told in its own prompt that it does not know what was said. This
is true whether the switch came from the `switch_companion` voice tool or from a tap on the
Companions screen.

## Context

`GeminiLiveSession` sends `systemInstruction` and `speechConfig.voiceName` once, in the setup message
at `onOpen`. There is no Live API message for mutating either on an open socket. The persona clause
and the voice are therefore **properties of the socket, not of the app** - which means "switch
companion" cannot be a state change at all. It is a reconnect, costing roughly a second of dead air.

Kevin asked for the switch by voice ("hey can i talk to dorothy"). Building it surfaced that the
hands path had never worked either: `CompanionProfileStore.switchActive` wrote the choice and had no
session-layer caller, so nothing invalidated `AriaBrain`'s two-minute base-instruction cache and
nothing rebuilt the socket. Tapping a different companion changed the label and nothing audible.

## Decision

One path for both doors: `LiveSessionController.companionChanged()`. Idle, it rebuilds the warm
socket through `refreshIdleVoice`. Mid-conversation, it destroys the socket and cold-connects a new
one on `HANDOVER_PROMPT`.

The voice tool **arms rather than fires**, exactly as `end_conversation` does and for the same reason
plus one: the outgoing companion has not spoken its handover line when the tool returns, and the
reconnect gap must land after that line rather than through it.

**The session resume handle is deliberately dropped.** It points at the outgoing conversation's
server-side history, and whether a resumed session honours a changed `systemInstruction` is
undocumented and untested. Carrying a thread into a different register is the wrong default anyway:
the user asked for somebody else, not for the same conversation in a new voice.

## Consequences

- **The incoming companion is told it has no memory of the handover.** `HANDOVER_PROMPT` is shaped
  after `THREAD_LOST_PROMPT` for CLAUDE.md sec 7's honesty reason - it must not claim a continuity it
  does not have. Unlike a lost thread, nothing was "cut off", so it must not apologise either.
- **A hand switch mid-conversation is no longer silent.** `.scratch/hands-and-senses/issues/13-voice-persona-surface.md`
  left "acknowledged or silent?" judged-but-undecided for a settings edit. A change of *who is
  speaking* is not a settings edit; saying nothing while a different voice answers is the uncanny
  option, not the quiet one.
- **A dismissal outranks a handover.** If both are armed on one turn, the conversation ends.
- **Asking for a built-in with no profile row creates one** from the persona's own defaults, so
  "put Kratos on" works before Kratos has ever been created on the Companions screen. It is an
  ordinary profile afterwards, renameable and deletable there like any other.
- **Name matching is exact after normalising, never fuzzy** (`CompanionSwitch`). A near-miss returns
  the list of companions that do exist and costs one clarifying turn; a fuzzy match would hand the
  conversation to the wrong person silently. This is sec 4's posture about an unverifiable figure,
  applied to a name.
