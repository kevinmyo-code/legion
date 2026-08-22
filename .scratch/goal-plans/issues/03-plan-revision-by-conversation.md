---
map: goal-plans
ticket: 03
title: "Arguing with the plan"
type: build
status: open
status-detail: ""
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
