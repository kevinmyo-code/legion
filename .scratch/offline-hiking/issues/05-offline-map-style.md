---
map: offline-hiking
ticket: 05
title: "Decision: the offline map style (Outdoors, or Standard with topo added)"
type: decision
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-mapbox-offline-facts]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Decision: the offline map style (Outdoors, or Standard with topo added)

## Question

The nav screen uses Mapbox Standard with day/night `lightPreset` (mapbox-plus ticket 01). A hike
needs contours, hillshade and trails. Which style does the hike screen download and draw?

## Facts (research 01)

- **Outdoors v12** shows contours (Terrain v2: 10 m interval from z14, 20 m at z13), hillshade and
  trails (Streets v8 `class=path`). It is a **classic style, no longer maintained**: no fixes, no new
  features. No night preset.
- **Standard** has no contour, hillshade or topo option; its only trail switch is
  `showPedestrianRoads`.
- Both download as a style pack. DEM (`mapbox-terrain-dem-v1`, z14) works offline and is added to the
  tile region as an extra tileset.
- Neither documents which OSM tags become a drawn trail, nor uses `sac_scale`.

## Options

| Option | Topo | Effort | Risk |
|---|---|---|---|
| **A. Outdoors v12 as-is** | Contours, hillshade, trails, out of the box | S | Unmaintained; may be retired some day (then B). No dark look. |
| B. Standard + our own layers | Add Terrain v2 contour lines and labels and a hillshade layer into Standard's slots | M | We own the layer styling; keeps day/night and the nav look. |
| C. Standard, no topo | Trails via `showPedestrianRoads` only | S | Not a hiking map. |
| D. Either, plus 3D terrain from DEM | Real relief when tilted | +S | More download and battery; 3D off in battery saver. |

## Recommendation

**A for v1, DEM included in the region (D) but 3D terrain off by default.** Outdoors is the only
option that is a hiking map on day one. The DEM costs some megabytes and buys the 3D view and
on-screen elevation when Kevin wants it. **Plan B as the exit** if Outdoors is retired or its look
proves hard to read in sun; that is a contained change in the hike screen's style loader.

The nav screen keeps Standard. The two screens can use different styles; each style pack is
downloaded once and shared by every region (research 01).

## Verification owed

On the first download (ticket 13): confirm whether the Outdoors style JSON already declares a
`raster-dem` source (research 01 gap), and record packs per region and bytes.
