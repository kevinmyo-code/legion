---
map: ambient-listening
ticket: "03"
title: "What counts as a moment worth speaking into?"
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
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
