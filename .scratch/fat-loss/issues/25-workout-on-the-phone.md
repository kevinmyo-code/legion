---
map: fat-loss
ticket: "25"
title: "Phone: Today's workout card, the session tick-off view, voice"
type: build
status: built
status-detail: "built 2026-11-06 on feat/workouts, targeted JVM tests green, Roborazzi body-workout-*.png recorded; owed: engine deploy, install fast, one real workout ticked on the phone"
blockers: ["24"]
blocked-by: ["[[24-workout-programme-engine]]"]
tags: [ticket]
---
# Phone: Today's workout card, the session tick-off view, voice

Direction 2. `bodycoach/WorkoutSessionController.kt` (one controller for the screen and voice,
offline queue), `ui/body/WorkoutViews.kt`, `ui/body/WorkoutSessionHost.kt`. `get_todays_workout`
behind `ask_body`; `log_workout_set` ticks today's workout; `create_workout_plan` retired; the
BIO Plan list archived once; "Today's plan" replaced by "Training history" under More; the Meal
dialog lists saved meals.

## Verification owed on the phone

1. Engine deployed with `body_coach.0003`; install `fast`.
2. Body tab on a training day shows "Today's workout", Day A, calibration.
3. Start, tick three sets (one edited), airplane mode for one tick, back online, Finish and log.
4. "This week" reads 1 of 3; Training history lists the exercises.
5. Voice: "what's my workout today", "log bench 135 for 8", "swap squats".
6. Lists and the Calendar day no longer show "BIO Plan".

## Follow-ups found at review (2026-11-06, not built)

- `get_todays_workout` and `get_workout_gap` read through `WorkoutSessionController.today()`, which
  replays this phone's queued ticks first. They are Kevin's own idempotent ticks, never a
  model-made write, so ADR 0065's purpose holds; the clean fix is `today(replay = false)` from the
  toolbox.
- `GoalPlanAgent`'s `workout_goal` schema text and the `generate_goal_plan` / `accept_goal_plan`
  declarations still describe a drafted plan; `generatePlan` now sets up the programme. Reword.
- `test_an_extra_set_does_not_count_against_progression` only checks the next plan exists; make it
  assert the extra set leaves the next target unchanged.

## Workout reads (2026-11-06, branch fix/workout-reads)

Kevin's first voice session asked "what's the next day", "what are the three sessions" and "Day
B"; the only read behind `ask_body` was today's, so Alfred said the next day was Day A and that
there was no Day B. Built: `GET workout/` also returns `days` (A and B with the rules' targets),
`upcoming` (one week of sessions with dates, today counted until its workout is done),
`today_status` and `programme_words`; the phone's `get_workout_programme` says them behind
`ask_body`. Owed: an engine deploy (an older engine sends no `days`, and the tool says the
programme is unknown rather than missing), then the four questions again by voice.

- **Hands path is partial (ADR 0035).** By hand, Today's workout shows the next day and This week
  the training days; nothing on the phone shows Day A and Day B side by side, or the dates ahead.
  The web half is ticket 26; the phone needs a small programme view on the Body tab.
