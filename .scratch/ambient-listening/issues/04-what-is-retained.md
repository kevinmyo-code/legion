---
map: ambient-listening
ticket: "04"
title: "What is kept, for how long, and what is never written down"
type: grilling
status: closed
status-detail: "Out of scope 2026-08-21 - ambient listening retired"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# What is kept, for how long, and what is never written down

## Question

The engine accumulates a transcript in memory and periodically sends it to Gemini. **What survives
that, and where, is unspecified** - and it is the difference between a feature that is defensible and
one that is not.

1. **Does the transcript ever touch disk?** Today, trace it and say so rather than assuming. If it
   does not, that is a property worth protecting deliberately rather than by accident.
2. **How long is the in-memory window**, and what clears it - time, size, the end of a drive, the
   screen locking?
3. **May anything heard here enter the assistant's durable memory?** `CLAUDE.md` sec 7 is explicit
   that memory stays anchored to external falsifiable facts and that a persona may not invent
   unfalsifiable history with the driver. **An ambient transcript is the single easiest way to
   violate that rule**, because it produces plausible, personal, unverifiable material by the hour.
   The safe default is no, and the burden is on any yes.
4. **What does the Gemini call retain on Google's side**, on Kevin's own BYO key? Establish it from
   the API terms rather than assuming; if it is retained, that is a fact the consent copy in
   [The toggle, and the words next to it](02-the-toggle-and-its-words.md) has to state.
5. **Is there a way to see what it heard, or wipe it?** A feature nobody can inspect is one nobody
   can trust.

The crisis path deserves its own beat here: an always-listening feature makes `ai/CrisisDetector.kt`
far more likely to fire than push-to-talk ever did, and a transcript is exactly the material that
should not be retained when it does.

## Closed OUT OF SCOPE - 2026-08-21 (Kevin)

**Ambient listening was retired** (`.scratch/proactive-mode/issues/12-retire-ambient-listening.md`).
It could not satisfy the raise contract - the sub-agent authored the spoken line itself, so there
were no facts for the prompt to state - and it was dead code besides: the opt-in had no writer, so
it could never actually run.

This ticket is a decision about a feature that no longer exists. Closed out of scope rather than
left reading as pending work. Wayfinder's own rule applies: **a scope boundary is not a step on the
route**, so this does not appear in Decisions-so-far.

**The idea is not forbidden, only this implementation.** A listening mode that CAN state its facts
would be a fresh effort, not a resumption of this one.
