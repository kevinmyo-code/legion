---
map: fat-loss
ticket: "14"
title: "G1: the weight goal is shown and tracked"
type: build
status: built
status-detail: "built 2026-11-05: profile goal_weight_lbs (engine 0002) is the milestone and protein fallback, a bodyweight goal's target is copied there by the phone, GoalsPanel shows trend progress; owed: a weight goal set after deploy"
blockers: ["05"]
blocked-by: ["[[05-trend-and-waist]]"]
tags: [ticket]
---
# G1: the weight goal is shown and tracked

The weight goal was a dead end: no unit safety and no progress. Use the goal row with
`metric_key = bodyweight_lbs`; progress comes from the engine trend's milestone (ticket 05), rate
based, and the goal's own readback says it. The plan generator writes weight goals with that key.
