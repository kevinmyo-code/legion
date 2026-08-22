---
map: ambient-listening
ticket: "03"
title: "What counts as a moment worth speaking into?"
type: grilling
status: closed
status-detail: "Out of scope 2026-08-21 - ambient listening retired"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# What counts as a moment worth speaking into?

## Question

The engine hands its accumulated transcript to a `SubAgent` told to react **only when it is genuinely
a good, in-character moment**. That instruction is doing enormous work and has never been exercised
against real conversation.

1. **What is the bar?** "Genuinely a good moment" is not a specification. Answering a question asked
   of the room, correcting a fact, offering something only LEGION knows - these are different bars
   and the widest one produces an interrupting assistant.
2. **How often is too often?** Once a drive, once an hour, or only when directly addressed? A
   companion that speaks unprompted twice in ten minutes is a different product from one that does it
   twice a week.
3. **What must it NEVER react to?** The KDoc already names private-sounding conversation between
   passengers. What else - arguments, anything about a third party, anything a passenger says rather
   than the driver?
4. **Does being directly addressed change the bar?** An open transcript contains "hey <name>", so the
   reaction pass can notice being spoken to. That is a genuinely different case from volunteering.
5. **What happens when it gets it wrong?** There is no undo on something said out loud in front of
   a passenger.

This is a taste call and a values call, not an implementation one. `/grilling`, one question at a
time, and do not answer on Kevin's behalf.

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
