---
map: fat-loss
ticket: "26"
title: "Web: the Workouts tab shows the programme, not the old plan CRUD"
type: build
status: open
status-detail: "the engine routes exist (ticket 24); web /body Workouts still edits legacy workout_plans and workout_plan_items"
blockers: ["24"]
blocked-by: ["[[24-workout-programme-engine]]"]
tags: [ticket]
---
# Web: the Workouts tab shows the programme, not the old plan CRUD

`server/frontend/src/screens/body-workouts.tsx` still offers "Weekly plan" and "Planned exercises"
CRUD over the legacy tables, which nothing reads for training any more (ADR 0078). Replace them
with today's workout and the week from `GET /api/body/coach/workout/`, and the session tick-off
through the same routes the phone uses. The "Sets logged" panel can stay; finish writes there.
