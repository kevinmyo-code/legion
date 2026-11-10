---
map: nav-quality
ticket: "06"
title: "Build: route to the entrance, and measure wrong picks"
type: build
status: open
blockers: ["01"]
blocked-by: ["[[01-baseline]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Build: route to the entrance, and measure wrong picks

## Change

1. **Route to the entrance.** When a search result has `routablePoints` (tested: present in
   2.32.0), the destination's coordinate is the first routable point, not the POI's centroid. A
   mall's centroid is often a roof. Kept in memory like every hit.
2. **Measure a wrong pick.** Two new signals in `tools/nav_audit_report.py`, from lines that already
   exist:
   - **Re-search after start:** a `search` within 120 s after a `trip_start` (the user changed
     their mind or the pick was wrong).
   - **Cancelled near the end:** `trip_end reason=cancelled` with `distance_left_m` under 300 (the
     user gave up circling).
3. Optional, if 1 and 2 are not enough: on `trip_end`, `end_gap_m`, the straight distance from the
   last fix to the destination, rounded to 10 m.

## Proven by NAV_AUDIT

Both wrong-pick rates go down after 1 lands, against ticket 01's baseline computed with the new
report. The report change lands first, so the baseline has them.
