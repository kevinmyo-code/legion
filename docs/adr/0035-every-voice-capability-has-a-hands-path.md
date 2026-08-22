---
status: accepted
decided: 2026-08-22
decided-by: Kevin
source: "[[decisions#2026-08-22 - every voice capability must also be reachable by hand]]"
tags: [adr]
---

# 35. Every voice capability has a non-voice path

## Standing

**Anything LEGION can do by voice must also be doable by hand.** A capability that exists only as a
voice tool is not finished, and a feature ticket that ships one is not done.

Kevin, 2026-08-22: *"all voice capabilities also must have a non voice UI capability."*

## Why

Voice is the fastest way in, and it is the way that fails. It fails in a loud car, next to a
sleeping person, in a meeting, when the wake word does not fire, when the microphone opens deaf,
when the socket is closed, when there is no key, and when the model mishears a name. Every one of
those has been observed on the real phone in the last week, not imagined.

**When the only path to a capability is voice, every one of those failures becomes total.** The
`answer_call` tool is the clearest case: a call arrives, the assistant mishears "answer it", and
there is no button - the capability effectively does not exist for that call, and the call is gone.

There is a second reason, and it is the one that survives every reliability fix: **a voice-only
capability is invisible.** Nobody can discover it, nobody can see its current state, and nothing
shows what it did. `docs/voice.html` exists precisely because the surface had grown unknowable from
the app itself.

## What it does NOT mean

- **Not screen parity for every parameter.** A tool taking eight arguments does not need eight
  fields. The hands path must reach the CAPABILITY, not mirror the prompt.
- **Not a second implementation.** Both paths call the same controller. Two implementations of one
  capability is how they drift into disagreeing, which is worse than one path.
- **Not a blocker on the observing tools.** A tool that only reads and speaks (`get_sitrep`,
  `ask_fleet`) is satisfied by the screen that already renders that data.

## Consequences

**The existing surface does not comply and pretending otherwise would make this decoration.** 66
tools are declared to the model; a large share have no hands path today. The size of that gap is
unmeasured, and measuring it is the first work, not a survey to be skipped -
`.scratch/hands-and-senses/issues/27-*.md` owns it.

Going forward the feature-add checklist carries the rule, so a new voice tool arriving without a
hands path is caught at the point it is written rather than found later.
