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

## Amendment, 2026-11-06: Alfred designs from the catalog; sessions a week; gym or home

Kevin: *"i want alfred to be able to design track and recommend workouts diets meals etc for me and
advise me"*. **The model may now design** (exercise choice, split, swaps, advice), but only by
choosing from what the engine returns: the catalog's exercises, its two templates and the
alternatives a slot lists. **Every number is still the rules'.** A plan-level change is a
**programme change proposal** (`server/body_coach/programme_change.py`) built by the engine from
structured choices and applied only when Kevin accepts it; an in-workout swap stays immediate.

- **Sessions a week, not weekdays** (2 to 4). The next session is the next in the rotation; the
  weekdays stored on the programme are inert. "Training days are settled in code" above now reads
  "the sessions-a-week target is settled by an accepted proposal".
- **Templates:** full body (A, B) and upper / lower (Upper 1, Lower 1, Upper 2, Lower 2); full body
  by default for 2 or 3 sessions, upper / lower for 4.
- **The lower body defaults to knee-friendly work** (split squats, wall sits, hip hinges); a heavy
  deep squat only when he asks.
- **Equipment and joints filter the catalog**; a slot with nothing allowed is paused and named,
  never filled with a guess. **A session is at the gym or at home** (a kettlebell and bodyweight).
- **A walking finisher** is optional, in minutes.
- Progression is read per exercise across all finished sessions, so it survives any change where
  the exercise survives; a new exercise calibrates. Map `.scratch/body-coach-2/`.
