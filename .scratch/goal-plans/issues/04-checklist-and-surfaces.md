---
map: goal-plans
ticket: 04
title: "The daily checklist, on Body and Home"
type: build
status: built
status-detail: "Both surfaces built. BLOCKED ON A DECISION: a recurring item cannot be ticked, so the checklist is followable and skippable, not tickable. Kevin asked for tickable."
blockers: ["02"]
blocked-by: ["[[02-recommender-and-playbook]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# The daily checklist, on Body and Home

## What to build

Kevin: *"revamp of BIO/body tab + revamped section in home tab"* (settled decision 7).

The checklist is what makes a plan a habit rather than a document. A day's items derived from the
accepted plan, tickable, visible at a glance.

- **Body tab**: the full picture - the plan, the targets, today's items, recent adherence.
- **Home section**: today's items only, at a glance, on the screen he actually lands on.

Ticking by voice should work through `manage_item`, which already handles exactly this shape - **do
not build a second ticking path.**

## Rules

- `mission-control` owns the aesthetics. Coordinate with it rather than inventing a look.
- **Adherence is shown, never scored.** A streak counter is a compulsion mechanic and CLAUDE.md §7
  bans it permanently. Showing which days had items completed is a record; ranking him on it is not.
- An empty or unaccepted plan reads as "no plan yet", never as zero progress. Same posture as
  `DigestText`'s "not logged, never 0".

## Verification

- Suite green on the pure derivation of a day's items from a plan.
- On the phone: both surfaces, with a real accepted plan, plus the empty state before one exists.

---

## Settled at build time (2026-08-22)

Tickets 02 and 03 both deferred plan storage. It is decided here, and the answer is that the
question dissolves rather than gets answered.

| Question | Ruling | Why |
|---|---|---|
| Where does an accepted plan live? | **Nowhere new.** The accepted plan IS the four writes the existing tools already make: `MealTarget`, `SleepTarget`, `WorkoutPlan` plus `WorkoutPlanItem`, and `Goal`. The Body tab reads them back. | All four tables already exist with DAOs. A `plan` entity would duplicate persisted state and give two answers to "what is my calorie target". |
| How does a day's checklist work, and how is it ticked? | **Repeating items on the existing single persistent list** (`ItemList` / `ListItem` / `ListItemSkip`), created at acceptance. | `manage_item` already carries `repeat_*` scheduling and skip-one-occurrence. That machinery IS a daily habit checklist. Making the items ordinary list items is what makes the ticket's "do not build a second ticking path" true for free - voice ticking already works, with no new tool. |

**No Room migration is expected.** If a build concludes it needs one, that is a signal the shape
above was misread, and it stops rather than writing it.

## The adherence trap, flagged before it is built

"Recent adherence" is only showable if completion history exists. If `ListItem` carries a current
done flag and no history, then the honest surface shows what is true and the gap gets a named
follow-up. **Rendering a plausible chart from data the app does not have would be fabricating a
record of Kevin's own behaviour**, which is worse than showing nothing, and it is the same failure
as a zero standing in for "unknown".

---

## The premise of the ruling above was FALSE, found while building (2026-08-22)

**A recurring `ListItem` cannot be ticked.** `NotesController.tick` refuses one outright
(`if (item.repeatKind != null) return false`), by design: notes-lists-calendar ticket 04, Kevin
2026-08-07, *"a repeat is an event you attend, not a chore you complete."* That decision removed
per-occurrence completion state on purpose, because it is what made recurrence expensive.

So the ruling that `manage_item`'s repeat machinery "IS a daily habit checklist" was wrong. It is a
recurring reminder that can be SKIPPED, which is an explicit opt-out, not a completion.

**Consequences, all of them real:**

- A day's item is followable and skippable by voice. It is **not tickable**, which is the word both
  Kevin and this ticket used.
- **No completion history exists anywhere in the schema** for a recurring item, only skip dates. So
  "recent adherence" cannot be a completion record. What shipped is an explicit-skip record, worded
  as exactly that, because rendering a completion chart from opt-out data would be fabricating a
  record of Kevin's own behaviour.

**This is a decision for Kevin, not a bug to fix quietly**, because every way forward either
reverses a standing decision or changes what the checklist is. Left open deliberately.
