---
map: fat-loss
ticket: "14"
title: "G1: the weight goal is shown and tracked"
type: build
status: open
status-detail: ""
blockers: ["05"]
blocked-by: ["[[05-trend-and-waist]]"]
tags: [ticket]
---
# G1: the weight goal is shown and tracked

The weight goal was a dead end: no unit safety and no progress. Use the goal row with
`metric_key = bodyweight_lbs`; progress comes from the engine trend's milestone (ticket 05), rate
based, and the goal's own readback says it. The plan generator writes weight goals with that key.
