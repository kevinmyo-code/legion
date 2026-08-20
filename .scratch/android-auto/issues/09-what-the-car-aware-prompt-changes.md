---
map: android-auto
ticket: 09
title: "What does the car-aware prompt actually change?"
type: grilling
status: kiv
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# What does the car-aware prompt actually change?

## Question

Settled decision 4: same brain, same 69 tools, one car-aware prompt variant. No tool allowlist, no
read-only mode. **All the driving safety is therefore carried by the prompt**, which makes its
wording the entire safety story rather than a bit of tone guidance. Decide what it says.

This ticket is unblocked and takeable now - it depends on no research.

Decide:

1. **Length and shape.** A driver cannot read. Does the car prompt cap answers (a sentence or two),
   ban lists outright, ban "see the screen for details", ban asking a question that needs a
   multi-part spoken answer?
2. **Confirmation posture on writes.** All 69 tools are live, including destructive ones. Does the
   car variant require a spoken confirmation before a write, always ("added to the shopping list -
   say undo if that is wrong"), or never? A driver cannot inspect what happened.
3. **What it declines to do while driving**, if anything. Statement ingestion, editing a note's text,
   `accept_proposal` on an advisor proposal. Note the tension: settled decision 4 deliberately
   refused a hard allowlist, so anything here is the *model* declining, which is softer and can fail.
   Kevin should say whether that softness is acceptable or whether decision 4 needs re-opening.
4. **Does it know it is in the car, or that Kevin is driving?** They are not the same - parked in the
   driveway with the head unit on is projection without driving. Is there a signal worth using (the
   call being active, OBD reporting road speed, Android Auto's own drive-state), and does the prompt
   change between them?
5. **Voice and register.** `ai/AssistantIdentity.kt` is still placeholder copy by its own doc comment
   and the assistant's actual voice has never been written (CLAUDE.md §10). Does the car surface wait
   for that, or does it get its own terser register now? **A prompt variant of a placeholder is still
   a placeholder** - say so plainly if that is the answer.
6. **Where it lives.** One appended car-context block, a separate system prompt, or a flag the
   existing prompt builder reads. Whichever keeps it from drifting out of sync as the phone prompt
   changes.
7. **Crisis handling.** `ai/CrisisDetector.kt` still routes genuine distress and stops performing the
   character - CLAUDE.md §7 makes that non-negotiable and it is the one rule that is not a matter of
   taste. Confirm it is unchanged in the car, and note the known gap: the crisis resource is US-only
   (988), and a person in the car is somewhere specific.
