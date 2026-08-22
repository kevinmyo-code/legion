---
map: goal-plans
ticket: 03
title: "Arguing with the plan"
type: build
status: built
status-detail: "Revision by regeneration, constraint memory and the two voice tools all in. Owes the on-phone run: state a constraint, regenerate, confirm it survived."
blockers: ["02"]
blocked-by: ["[[02-recommender-and-playbook]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Arguing with the plan

## Why this is not a v2 feature

Kevin, defining the destination: *"accept recommended with user being able to tweak the plan or ask
the agent - hey i dont have access to a gym, can we mix in kettlebell workouts etc."*

**A plan you can only accept or reject is a generator. A plan you can talk to is a coach.** Settled
decision 2.

## What to build

A revision path: Kevin says what is wrong in his own words, the recommender returns an amended plan,
he accepts that. The constraint he states should persist - having said once that he has no gym, he
should not have to say it again on the next regeneration.

## Decide while building

- **Is a revision a new plan, a diff, or an edit in place?** This is genuinely open and depends on
  the storage shape, which is why the map lists it under "not yet specified" rather than deciding it
  here.
- **Where a stated constraint lives.** It is closer to a durable fact about Kevin than to a plan
  field - "no gym access" outlives any one plan. `CompanionMemory` is the obvious home and may be
  the wrong one; decide deliberately.
- **How a revision that contradicts the doctrine is handled.** "Cut to 800 calories" is a request the
  playbook's own boundaries should refuse. **The refusal must be the feature**, exactly as it is for
  `get_reported_crime_history` - it says plainly what it will not do and why, then offers what it
  can.

## Verification

- Suite green on the pure parts.
- On the phone: state a constraint, get an amended plan, regenerate, and confirm the constraint
  survived.

---

## Settled at build time (2026-08-21)

The three calls this ticket left to "decide while building", decided.

| Question | Ruling | Why |
|---|---|---|
| Is a revision a new plan, a diff, or an edit in place? | **A new plan**, regenerated from the original goal prose plus every constraint stated so far. | There is nothing to diff against - ticket 02 deliberately built no plan storage, and inventing one here would pre-empt ticket 04. Regeneration also keeps a revised plan internally consistent: editing "no gym" by hand would leave a calorie target reasoned from a workout plan that no longer exists. |
| Where does a stated constraint live? | **`CompanionMemory`, category `DRIVER`** (the existing allowlisted stored value). No new table. | "No gym access" is a durable fact about Kevin that outlives any one plan, and recall is already cross-vehicle. A second store of facts about him is the same failure as a second playbook. **Only what he actually SAID is persisted** - an inferred constraint is unfalsifiable memory, which CLAUDE.md §7 forbids. |
| A revision that contradicts the doctrine? | **Refused individually.** The existing plan survives, the one change is refused, it says what it will not do and why, then offers what it can. | Identical to settled decision 9. `HARD_FLOOR_CALORIES_KCAL` in `GoalPlanAgent.parse` is unconditional and keeps applying to a revised plan for free. |

## Scope added deliberately: the voice reach

**This ticket's own verification is impossible without it.** It says *"On the phone: state a
constraint, get an amended plan, regenerate, and confirm the constraint survived"* - and ticket 02
built no surface whatsoever, so nothing on the phone can reach a plan at all. CLAUDE.md §8 L11
makes a ticket's verification steps gates rather than notes, so the choice was to build the reach or
to declare the step deferred on day one.

So ticket 03 adds the **voice tools** for generating and revising a plan. **UI screens stay in
ticket 04.** Acceptance remains ONE consent over the whole plan (settled decision 14), and
`GoalPlanAgent.accept()` stays the only thing that writes.
