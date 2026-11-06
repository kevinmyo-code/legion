---
map: nav-quality
ticket: "09"
title: "Build: lanes on the banner, measured by missed turns"
type: build
status: open
blockers: ["01"]
blocked-by: ["[[01-baseline]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Build: lanes on the banner, measured by missed turns

mapbox-plus ticket 04 owns the build: congestion colours, lane guidance, speed limit. This ticket
is its measuring stick, so the build has to show that it helps.

## Measure

- **Missed-turn proxy:** `reroute ... reason=off_route` per guided hour (`reroutes_per_hour` with an
  off-route reason). An off-route reroute is almost always a missed or wrong turn.
- **Cue quality:** `cue_drops` superseded per trip. A cue replaced before it was said is a turn the
  driver never heard. If it is high, cues bunch at complex junctions, and the lane picture matters more.

## Done when

The off-route reroute rate after mapbox-plus 04 lands is below ticket 01's baseline over a
comparable number of guided hours. Or the ticket says plainly that it did not move.
