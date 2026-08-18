---
map: cyberdeck-ui
ticket: 16
title: "Build: BIO rebuild"
type: task
status: resolved
status-detail: ""
blockers: ["13", "14"]
blocked-by: ["[[13-build-shell]]", "[[14-build-chart-kit]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Build: BIO rebuild

## Question

Rebuild BodyScreen per ticket 07: MASS/INTAKE/SLEEP/TRAINING sparkline panels; drilldowns with
full charts + history lists (recent-log lists move here); TRAINING -> exercise list ->
per-exercise progression (weight x reps over weeks - needs a new DAO query grouping
WorkoutSetLog by exercise over time); `UPLINK: SELF-REPORT` header; range selectors in
drilldowns only. Day/week windows use the device-zone boundary helpers (dayStartEpoch/
weekStartEpoch), never UTC.

## Answer

Built 2026-08-08 (coding agent, worktree, merged). Four fixed panels + five in-screen drilldowns
(sealed BodyDrilldown state + BackHandler, two-level TRAINING pop; per-drilldown state vars to
sidestep the 4fd241e race class by construction). New DAO reads (BodyweightLog forWindow,
distinctExercisesByRecency, forExercise) - @Query additions only, no schema change. Bucketing
built on DeckChartData; gaps null end-to-end; UPLINK // SELF-REPORT stated once; ALL-range
progression anchors to the exercise's oldest set, not epoch 0. 13 new resolver tests incl. a
DST spring-forward day-boundary case; compile + full suite green (tested). Deferred to 21:
on-device drilldown/back QA; known follow-up: blank x-axis date ticks on drilldown charts,
DeckRange.ALL epoch-walk on INTAKE/SLEEP for long-lived installs.
