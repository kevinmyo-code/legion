---
map: offline-hiking
ticket: 14
title: "Build: the hike screen (offline map, own GPS puck, compass heading)"
type: build
status: open
status-detail: ""
blockers: ["05"]
blocked-by: ["[[05-offline-map-style]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Build: the hike screen (offline map, own GPS puck, compass heading)

**Release:** v1. **Size:** M.

## What

A new screen, reached from the nav screen and from HOME's Maps bucket, that shows the offline map
for wherever Kevin is, with his position and heading, and works in airplane mode.

## Rules

- **No `MapboxNavigation` and no trip session.** The hike screen is a `MapView` (Maps SDK, already in
  the build through `ui-maps`) with the location puck fed by the phone's own GPS. A trip session
  bills and drains the battery (ADR 0054 consequences); a hike never starts one. (traced:
  `navigation/MapboxNavSdk.kt` owns the only `MapboxNavigation`.)
- Style per ticket 05. The style must load from the offline style pack with no network; the
  screen must never block on a style fetch.
- Puck: GPS fix from `LocationManager.GPS_PROVIDER` (no network provider needed; works with no
  signal). Accuracy circle shown, so a 50 m fix does not look like a 5 m one.
- Heading: the phone's rotation-vector / magnetometer heading (research 03 on the A25's sensors).
  Compass calibration hint when accuracy is low, in words.
- Map follows the puck until Kevin pans; a button re-centres.
- Coverage: when the camera is outside every downloaded region, a banner says so in words
  ("No offline map here.") and the puck and track still draw on the blank map.
- Scale bar and Mapbox attribution ornaments kept (ToS 1.4).
- Hilt: `HikeViewModel` with one `StateFlow<HikeUiState>`; the controller is an injected class.

## Verification

- Unit: the coverage check (camera bbox against stored regions), pure.
- Compile, suite green.
- On the phone, airplane mode on, after a download: map renders, puck moves on a walk round the
  block, heading follows the phone. Owed to ticket 19.
