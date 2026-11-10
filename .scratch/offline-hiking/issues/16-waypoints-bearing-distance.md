---
map: offline-hiking
ticket: 16
title: "Build: waypoints (car, trailhead, marked spots) with bearing and distance"
type: build
status: open
status-detail: ""
blockers: ["14"]
blocked-by: ["[[14-hike-screen]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Build: waypoints (car, trailhead, marked spots) with bearing and distance

> **Covered by `feat/field-nav` (round 5, 2026-11-10), not merged.** The Field screen (its own app
> drawer entry) marks "car" and named spots, local first (`sync_outbox`, target `field_waypoints`)
> and synced into places through `PlaceController.savePlaceAt`; bearing (great-circle initial,
> true and magnetic via `GeomagneticField`), straight-line distance said as straight line, and an
> arrow relative to the compass heading (`field/FieldState.kt`, `FieldReadoutTest`,
> `FieldWaypointsTest`). Not built here: "trailhead = first point of a recording" (needs ticket 15)
> and the elevation difference (needs a DEM or a second fix's altitude). When the hike screen
> (ticket 14) lands it should reuse `FieldReadout` and `FieldWaypoints`, not a second copy.

**Release:** v1. **Size:** S.

## What

- "Mark car" and "Mark this spot" on the hike screen, one tap each. "Trailhead" is the first point
  of a recording unless Kevin marks one.
- Pick a waypoint: a compass arrow points to it, with straight-line distance and the elevation
  difference when known.
- Waypoints are places (ticket 08), written locally first and synced when signal returns.

## Rules

- Bearing: great-circle initial bearing from the fix to the waypoint, minus the phone heading.
  Magnetic declination applied with Android's `GeomagneticField` (offline, no network).
- Distance shown as straight-line and says so ("1.2 mi straight line"); it is not trail distance.
- A waypoint name is Kevin's own words. No reverse geocoding offline (there is none).

## Verification

- Unit: bearing and distance against known pairs, declination applied, arrow angle wrap at 0/360.
- Compile, suite green.
- On the phone: walk away from a marked spot, arrow points back. Owed to ticket 19.
