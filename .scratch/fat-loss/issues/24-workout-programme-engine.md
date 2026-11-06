---
map: fat-loss
ticket: "24"
title: "Engine: the deterministic workout programme, sessions and sets"
type: build
status: built
status-detail: "built 2026-11-06 on feat/workouts; tests/test_workouts.py, tenancy and recruiter fences green; owed: engine deploy with body_coach 0003"
blockers: ["23"]
blocked-by: ["[[23-workouts-replace-the-tickboxes]]"]
tags: [ticket]
---
# Engine: the deterministic workout programme, sessions and sets

`server/body_coach/programme.py` (rules), `workouts.py` (rows), `workout_views.py` (routes under
`/api/body/coach/workout/`), migration 0003 (three born-tenanted tables), engine tools
`get_todays_workout`, `start_workout`, `tick_workout_set`, `swap_exercise`, `finish_workout`.
Finishing writes legacy `workout_set_logs` once. Recruiters refused.

Owed: an engine deploy with migration `body_coach.0003`, then one `GET /api/body/coach/workout/`.
