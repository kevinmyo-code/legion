---
status: accepted
decided: 2026-11-06
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-11-06 - Workouts the engine writes, ticked off on the phone; the old tickboxes go (Kevin)]]"
tags: [adr]
---

# 78. Workouts come from rules, not from the model

## Standing

ACCEPTED 2026-11-06. Map `.scratch/fat-loss/`, tickets 23 to 26.

## Context

Kevin wants LEGION to make workouts he can tick off (*"i wanna have it make workouts for me that
i can tick off"*). Two model-written training surfaces already existed: a Gemini sub-agent that
invented a weekly plan (`create_workout_plan`), and the BIO advisor's daily tickbox list, whose
ticks recorded nothing. He found them confusing. A number the model makes up is a number nobody
can check, and progression only works if next week's weight follows from this week's sets.

## Decision

- **Every workout number comes from `server/body_coach/programme.py`**: templates (3 full-body
  days, A and B), double progression, calibration on an exercise's first session, a stall reset to
  90%, a deload every sixth block of finished sessions. Voice, the web assistant and the advisor
  may explain a target or swap an exercise for one of its listed alternatives; they never write or
  change a number.
- **The engine owns the programme, the sessions and the sets** (`training_programmes`,
  `workout_sessions`, `workout_sets`). Each set stores the rule's target and name beside what was
  done, so any progression can be re-derived from the rows.
- **The phone never computes a target.** It shows the engine's, sends only what was done, and may
  tick offline (queued, said in words).

## Consequences

- `create_workout_plan` and `WorkoutPlanAgent` are gone; the BIO advisor cannot write a checklist
  or a workout plan.
- A new template is a code change with tests, not a prompt.
- Training days, units (pounds) and increments are settled in code; changing them is a ruling.
