---
map: offline-hiking
ticket: 01
title: "Research: Mapbox offline maps and topography on Maps SDK 11.32"
type: research
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Research: Mapbox offline maps and topography on Maps SDK 11.32

## Question

What can the Maps SDK already in the build (11.32, through Nav SDK 3.32.0's `ui-maps`) do offline:
the download API, limits, size per area, topo layers, elevation, the terms and the cost?

## Answer

Findings: [research/01-mapbox-offline-maps.md](../research/01-mapbox-offline-maps.md) (2026-11-09).

- Offline = a style pack plus a tile region, through `OfflineManager` and the one `TileStore`
  (shared with the Nav SDK). No automatic refresh. 11.32 reports a partial download as
  `PARTIAL_LOAD`, not success.
- Limit: 750 tile packs per tile store. A 10 km x 10 km park at z10-16 is about 7 packs per tileset
  (reasoned). Disk quota unlimited by default, settable in bytes.
- No published MB per km². Measure with `estimateTileRegion` before and `completedResourceSize` after.
- Outdoors v12 has contours (10 m from z14), hillshade and trails, and is a classic style no longer
  maintained. Standard has no topo mode. DEM (`mapbox-terrain-dem-v1`, to z14) works offline.
- `getElevation` answers only for the visible map with terrain on; no track-wide lookup.
- Terms: "offline" appears nowhere; 2.8.1 caps device caching at 30 days and at the SDK's limits.
  Refreshing each region within 30 days is the safe reading. No bundling or sharing of tiles (1.10).
  1.3.4 forbids uses where failure could be expected to cause injury: LEGION must not be the only
  safety tool.
- Cost: MAU billed, 25,000 free; offline downloads count inside MAU. Two users is $0.
