---
map: goal-plans
ticket: "08"
title: "The checklist prescribes a day, and a ticked day logs itself"
type: build
status: built
status-detail: "Built at v32: per-day prescriptions summing exactly to weekly volume, reps on new plans, lazy end-of-day auto-log anchored on ListItem.loggedAt, TRAINING pane retired with its hands paths relocated. Owes the on-phone day cycle."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# The checklist prescribes a day, and a ticked day logs itself

Kevin, from the phone, 2026-08-22, verbatim - this is the ruling:

> *"bio page > training and checklist > retire training page. delete it. checklist > seems like its
> a weekly goal. kettlebell swing > 12 sets this week. it should be daily. 3 sets x 10 rep
> kettlebell swing etc. and i check it off. end of day it checks what i ticked and logs it. next
> day checkboxes reset"*

## Why it rendered weekly

Ticket 07 assigned DAYS but the line still rendered the plan row raw, and `WorkoutPlanItem` carries
only `exercise` + `targetSetsPerWeek` - no per-day split, no reps. "12 sets this week" is the
schema showing through.

## Build

1. **Delete the TRAINING pane from BodyScreen.** The checklist supersedes it. **ADR 0035 must
   survive the deletion**: the `+ LOG SET` dialog (ad-hoc sets outside any plan) and the recent-sets
   / exercise-progression view move into the checklist pane's expanded view or drilldown - the
   hands path relocates, it does not vanish. `ShippedVisualisationsTest` entries per protocol.
2. **Daily prescription lines.** An exercise assigned to N days renders per day as its share:
   12 sets/week over 4 assigned days is "3 sets - Kettlebell swing" on each. Uneven splits
   distribute the remainder deterministically (earlier days heavier), never fractional, never
   silently dropped - the week's lines must sum to `targetSetsPerWeek`.
3. **Reps, without fabrication.** The plan has no reps column, and inventing "x 10" would be a
   made-up prescription. Additive migration (v32): nullable `repsPerSet` on `WorkoutPlanItem`,
   plumbed through `WorkoutPlanAgent`/`GoalPlanAgent`'s generation schema so NEW plans carry reps;
   a null renders sets-only. Full migration discipline: verbatim generated SQL, exportSchema,
   identity-hash check, `SCHEMA_VERSION` bumped with `@Database`.
4. **End-of-day auto-log, lazily.** No new alarm: when materialization first runs on a NEW day, it
   sweeps PAST days' plan workout items that are ticked and not yet logged, and writes each through
   `WorkoutController.logSet` - the same function voice and the dialog use - with the prescribed
   sets/reps, **timestamped to the item's own day, not "now"**, same trust tier a spoken log gets
   (he reported it either way).
   - **Idempotent, provably.** No double-log on repeated opens. If the clean anchor needs an
     additive nullable column on `ListItem` (e.g. `loggedAt`), it shares migration v32. The
     adherence record must survive - logging a ticked item must not delete or untick it.
   - **Workout lines only.** A ticked meal or sleep line stays a record of the tick; auto-logging a
     meal would invent calories nobody stated.
5. **Checkbox reset daily** - already true via day-scoped materialization; keep it true.

## Verification

- Suite green both ways, one run fresh. Migration test for v32.
- Tests: weekly volume sums across the week's lines exactly; remainder split deterministic; a
  ticked past day logs exactly once across three materialization runs; the logged row carries the
  item's day, not the sweep's day; meal/sleep ticks log nothing.
- On the phone: a day's line reads like a prescription, tick it, next day it is logged and the
  boxes are fresh.
