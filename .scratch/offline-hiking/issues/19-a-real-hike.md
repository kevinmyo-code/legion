---
map: offline-hiking
ticket: 19
title: "Test: a real state-park hike on the A25 in airplane mode"
type: test
status: open
status-detail: ""
blockers: ["13", "14", "15", "16", "17", "18"]
blocked-by: ["[[13-offline-areas-screen]]", "[[14-hike-screen]]", "[[15-track-recorder-and-backtrack]]", "[[16-waypoints-bearing-distance]]", "[[17-sunset-turnaround]]", "[[18-share-trip-plan]]"]
open-blockers: 6
ready: false
tags: [ticket]
---
# Test: a real state-park hike on the A25 in airplane mode

**Release:** v1 gate. **Size:** one hike, plus a walk round the block first.

## What

The v1 builds proven on the phone, with no signal, before anything v2 starts.

## Checklist

1. At home on Wi-Fi: download the park's area; note the size the screen reports and the size on disk.
2. Share the trip plan to someone (ticket 18). Confirm it arrived.
3. Airplane mode ON before leaving the car park. Location ON.
4. Hike screen: map and trails render; puck moves; heading follows the phone.
5. Mark the car. Start recording.
6. Screen off for at least 30 minutes; recording continues (notification present).
7. Mid-hike: distance, ascent (labelled estimate), sunset line, battery %. Compare distance with any
   posted trail sign.
8. "Back to start" and the car arrow agree with reality on the way back.
9. Battery: % at start and end, and which mode (normal or saver). This number sizes the sampling
   defaults in ticket 15.
10. Back on signal: nothing crashed; the GPX exports and opens in another app.

## Record

Results in `.legion/reports/` (outside the repo: it is a real location and a real day). The ticket
gets only pass or fail per step and the battery-per-hour figure.
