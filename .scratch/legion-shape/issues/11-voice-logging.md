---
map: legion-shape
ticket: 11
title: "How does logging by voice actually work?"
type: grilling
status: resolved
status-detail: "2026-08-07, Kevin"
blockers: ["05"]
blocked-by: ["[[05-target-log-gap-vocabulary]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# How does logging by voice actually work?

## Question

Ticket 01 decided voice is how you WRITE to the record, not only how you query it. This is the
capability the whole shape rests on, and `memory/MEMORY.md` says **no voice tool call has ever run**.

1. **The tool shape.** Fleet already writes by voice (`set_odometer`, `log_service`, `add_car_task`,
   `remember`). Is that shape right for a workout or a meal, or does logging need something else?
2. **Confirmation.** A misheard workout is a wrong record. Does the assistant read back before
   writing, always, never, or above some threshold? Note this trades against speed, which is the
   whole point of voice.
3. **Partial input.** "I did squats" has no sets, reps or weight. Does the assistant ask, guess, or
   store what it has? Ticket 02 says a guess is reported - but a guess about what you *did* is worse
   than a guess about a category.
4. **Correcting a mistake by voice.** "No, 235 not 225." Does that need an edit tool, and how does it
   find the row it just wrote?
5. **Everything logged by voice is a reported fact.** Confirm the tier tagging happens at the tool
   layer so no domain can forget it.
6. **The thing that has never been tested.** Before any of this is specified further, a voice tool
   call needs to actually run once on hardware. That may be its own task ticket rather than part of
   this decision.

---

## Resolution (2026-08-07, Kevin - D33-D37, and D38 withdrawn)

**D38 was withdrawn as already answered.** Kevin, on device, 2026-08-07: **voice tool calls run,
read AND write, on both fleet and ledger.** `memory/MEMORY.md` had claimed the opposite since session
3 and was still being cited as fact on 2026-08-06. Corrected in `9d39b1a`. The whole shape of this
map assumed voice logging would work; it does.

**33. Same tool shape as `set_odometer`/`log_service`.** Proven on hardware, so no reason to invent a
second pattern.

**34. No separate confirmation step.** The assistant states what it wrote as it writes it - "Three
sets of squats at 225, logged." Speed is the entire point of voice; a confirm turn doubles every
interaction to guard against a rare mishearing that D36 already fixes cheaply.

**35. Partial input stores what it has and asks ONCE for the important missing piece.** Not a
form-filling interrogation.

**36. Corrections use an "undo the last thing" tool.** Simplest mechanism that works, and it is what
makes D34 safe.

**37. Tier tagging happens at the TOOL layer**, not per-domain, so no domain can forget it. Everything
logged by voice is a reported fact by construction (ticket 02).
