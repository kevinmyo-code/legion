---
map: ambient-listening
ticket: "07"
title: "What does LEGION owe a passenger who never agreed to any of this?"
type: grilling
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-the-listening-indicator]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# What does LEGION owe a passenger who never agreed to any of this?

## Question

Kevin consents by flipping the toggle. **Nobody else in the room does.** The engine's own KDoc names
this as the reason ambient listening cannot inherit the wake word's consent, and then the design
stops there.

`CLAUDE.md` sec 7's safety amendment is deliberately narrow, and it was written about the assistant's
relationship with the two adults who installed it knowingly. A passenger is neither of them.

1. **Is the on-screen indicator enough**, given a passenger is not usually looking at Kevin's phone?
2. **Should there be anything spoken?** A companion that says out loud that it is listening when
   someone new gets in is honest and also awkward. Which does Kevin want?
3. **Is there a case for a quick off?** Something faster than Settings, for the moment a conversation
   turns private. Mute already exists as a hard listening gate - is that the answer, and is it
   reachable fast enough?
4. **Does the reaction bar change with a passenger present?** Ticket 03 sets the general bar; this
   asks whether the presence of someone who did not opt in should raise it.
5. **The honest floor:** if the answer to all of the above is "nothing", say so explicitly and record
   it as a decision Kevin made with his eyes open, rather than leaving it unasked.

This is the ticket most likely to conclude that some part of the feature should not ship as designed.
That is a legitimate outcome, not a failure of the map.
