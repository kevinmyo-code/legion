---
map: goal-keeping
ticket: 03
title: "The check-in record: how a spoken answer becomes a falsifiable fact"
type: grilling
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-what-on-track-means]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# The check-in record: how a spoken answer becomes a falsifiable fact

## Question

Settled decision 2 says an unmeasurable goal is asked about, never judged, and that Kevin's answer
becomes the record. That record does not exist. Nothing in `data/local/` stores an answer to a
question the assistant asked.

This is the ticket that makes decision 2 real rather than a posture, and it is load-bearing for the
whole map: without it, prose goals - which is most goals - have no history, and the app is back to
either silence or inference.

Decide:

1. **What is stored.** The question asked, the answer spoken, the timestamp, the goal it belongs to.
   Free prose, or a shape ("moved" / "stalled" / "done") with prose beside it? A shape is queryable
   and a shape is also the app putting words in Kevin's mouth.
2. **Where.** A new table, or rows on the existing goal lineage? `CompanionMemory` already stores
   distilled facts and is the wrong home - that is the assistant's memory of a conversation, this is
   a record about a goal.
3. **Trust tier.** `plan/TrustTier` splits reported from proven. A check-in answer is REPORTED by
   construction: Kevin said it, nothing verified it. Does it say so on every surface that renders
   one, the way `UNRECONCILED` must?
4. **How the answer gets captured.** A tool the model calls mid-conversation, or a deterministic
   parse of the reply? A tool is the house pattern, and CLAUDE.md's no-false-success rule means it
   must report honestly whether the row actually landed.
5. **What a non-answer means.** Kevin says "later", or nothing, or changes the subject. Silence is
   not "stalled", and treating it as data would be inventing a fact about him.
6. **Does the check-in cadence live here or on the goal?** Ticket 02 asks whether a goal declares how
   it wants to be kept; if it does, this ticket reads that rather than owning a schedule.
