---
status: accepted
decided: 2026-11-10
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-11-10 - Cut the Live tool list; scripted opening lines instead of a spoken greeting (Kevin)]]"
tags: [adr]
---

# 87. The Live tool surface is a budget; a tap opens the mic with a scripted line

## Standing

**About 15 always-loaded core tools plus helpers: at most 24 top-level Live declarations, under a
tools-JSON size budget**, both asserted in `LiveSetupPayloadSizeTest`. A new capability joins an
existing **action tool** (one declaration, an `action` enum naming the old tool, resolved to that
tool before anything else runs) or a **router** domain (a READ answered by the phone's
sub-agent), not a new declaration. Where
every pre-diet tool lives is `service/tools/LiveSurface.kt` (action tools) and
`LiveToolbox.DISPATCHED` (routers); `LiveSurfaceTest` fails on a tool with no home. Raising either
budget is Kevin's call, with the measured figure written beside it.

**A tap or the wake word asks the model for nothing.** The phone plays one scripted line in
Alfred's Puck voice (`assets/listen_cues/`, picked by `service/ListenCue.kt`; an earcon until a
clip exists) through Alfred's own track once the socket is up (`CUE_TIMING`, one constant to flip
to at-tap), and the mic opens when it has drained. The
per-conversation context rides the setup's system instruction (`LiveOpenContext`). First run, a
lost thread and Brief me still open with a model line.

## Context

The voice cost audit (2026-11-10) modelled about 86% of Live spend as the setup (system
instruction plus 92 declarations, about 23k tokens), billed as text input on every generation, and
13% of generations as greetings nobody asked for. Kevin: *"lets cut the toollist"* and *"drop the
spoken greeting and wake reply, can we seed a few scripted lines instead?"*, then 25 lines of his
own, then *"yes trim tools"*: about 15 always-loaded core tools plus helpers, not 40. Model vendors
advise 10 to 20 always-loaded tools.

## Decision

- 12 direct: `clock`, `play_music`, `control_music`, `navigate`, `manage_checklist`,
  `manage_item`, `end_conversation`, `claude_session`, `email`, `memory`, `browser`, `ask_engine`.
- 10 action tools: `trip`, `day` (calendar, brief, time, parcel, flight, watches), `phone` (calls,
  screens, camera, books, app update), `car`, `body_log`, `money` (with the bought log), `place`,
  `goal`, `records`, `voice_note`; plus extra actions on `manage_checklist`, `manage_item` and
  `control_music` (volume, library, queue). A write never goes through a second model: the call
  is renamed to the old tool and runs its own branch, confirm gates and write accounting included.
- 2 routers: `ask` (fleet, body, goals, pantry; NON_BLOCKING with "Answer lookups while talking")
  and `look_up` (mail, money, area; blocking). The domain values are the old router names.
- No read moved behind a router that was not behind one already, so nothing got slower.
- Long per-tool caveats that matter only after the call moved onto its result
  (`LiveSurface.RESULT_RULES`), billed per call rather than per generation.

## Consequences

- Selection now picks a tool and then an action: the routing eval (`tools/live_tool_eval/`)
  measures it per tool; the live half needs a key.
- Old names still dispatch, so a model or a result that names `log_meal` still works.
- The head unit and the web assistant keep their own setups; the same diet is not applied there.
