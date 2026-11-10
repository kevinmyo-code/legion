---
map: offline-hiking
ticket: 21
title: "Build: elevation profile for a planned line and a recorded hike"
type: build
status: open
status-detail: ""
blockers: ["15", "20"]
blocked-by: ["[[15-track-recorder-and-backtrack]]", "[[20-gpx-import-and-off-trail]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Build: elevation profile for a planned line and a recorded hike

**Release:** v2. **Size:** M.

## What

A chart under the map: elevation against distance, with "you are here" on it, total ascent and
descent, and the steepest stretch. For a planned GPX (ticket 20) and for the hike being recorded.

## The problem (research 01 §4)

The Maps SDK's `getElevation` answers only for points on the visible map with terrain on. There is
no public API for a whole line, and the DEM tile packs cannot be read directly. The web elevation
APIs are online only.

## Approach

1. **Recorded hike:** GPS altitude, smoothed, labelled an estimate (ticket 15 already keeps it).
2. **Planned line, prepared while online:** when a GPX is imported with signal, compute its profile
   once and keep it with the plan. Sources in order: elevations already in the GPX (`<ele>`); else
   a public-domain DEM service (for example the USGS Elevation Point Query Service, US only; check
   its terms when building) called from the engine, not the phone, sampled every 25 m.
3. **Planned line, already offline with no profile:** sample `getElevation` along the part of the
   line on screen, and say "partial profile" in words.
4. If ticket 06 picks BRouter, its tracks carry elevation from its own segments (reasoned; confirm
   in the spike).

## Rules

- Every elevation figure says where it came from (GPX, DEM service, GPS, map) and is labelled an
  estimate (§4 rule 5).
- A profile from a Mapbox source is never stored (2.10.1 is about routes, 2.8.1 about tiles; storing
  derived elevations from Mapbox tiles is not clearly allowed, so don't). USGS data is public domain
  and may be kept.

## Verification

- Unit: resampling, smoothing, ascent with hysteresis, the partial-profile wording.
- Server pytest for the engine elevation call with a fake service.
- Compile, suite green. On the phone: owed.
