---
map: legion-shape
ticket: 09
title: "What is the meals domain?"
type: grilling
status: resolved
status-detail: "2026-08-07, Kevin"
blockers: ["05", "07"]
blocked-by: ["[[05-target-log-gap-vocabulary]]", "[[07-categorisation]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# What is the meals domain?

## Question

Does not exist. `pantry/` reads grocery receipts, which is shopping, not eating. Kevin: *"meals same
. just an estimate of calories and macros. maybe cross checked against groceries and food spending."*

1. **What is a meal log entry?** A photo, a spoken description, or both? `PantryReceiptAgent` already
   does LLM vision on receipts, and `SubAgent` takes an inline image - the machinery exists.
2. **Where do calories and macros come from?** A food database, or an LLM estimate from the
   description? Ticket 12 researches whether a keyless database exists. **Either way these are
   reported facts and CLAUDE.md §4 rule 5 already requires them to read as estimates** - pantry's
   existing macro estimates set the precedent.
3. **What is the target?** A daily calorie and macro goal, presumably. Daily, or weekly average?
4. **What is the gap?** Distance from today's target, and what happens to a day you did not log -
   does an unlogged day read as zero eaten, which would be a lie?
5. **The cross-check.** *"maybe cross checked against groceries and food spending"* - the only
   cross-domain idea in the whole set. Is it in this domain's first loop, or deferred? Note it needs
   category parity between food spend and meals (ticket 07 question 1).
6. **The first closed loop.** Smallest useful version.

---

## Resolution (2026-08-07, Kevin - D25-D28)

**25. A meal is logged by voice OR photo.** Both already have machinery - `SubAgent` takes an inline
image, `PantryReceiptAgent` already does LLM vision.

**26. The target is daily calories and macros.**

**27. An unlogged day reads "not logged", never zero.** Zero eaten is a lie, and a gap computed
against it would be confidently wrong - the exact failure class section 4 rule 6 covers.

**28. The grocery cross-check is DEFERRED**, and stays in the map's fog. It is the only genuinely
cross-domain idea in the set and needs both domains working first. D15 (shared food categories) is
what keeps the door open without a migration later.

**Macros come from the LLM, labelled as estimates** - settled by ticket 12's research. No food
database fits LEGION's constraints. `PantryLineItem` already carries `caloriesKcal`, `proteinG`,
`carbsG`, `fatG`, so the storage shape exists.
