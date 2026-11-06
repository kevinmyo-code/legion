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
