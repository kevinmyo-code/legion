---
map: offline-hiking
ticket: 25
title: "Build: offline trail routing to a waypoint, trailhead or along a loop"
type: build
status: open
status-detail: ""
blockers: ["06"]
blocked-by: ["[[06-offline-routing-engine]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Build: offline trail routing to a waypoint, trailhead or along a loop

**Release:** v2. **Size:** M (Mapbox walking) or L (embedded BRouter with its segment download).

## What

On the hike screen, "Route to" a waypoint or trailhead, offline: a line along trails, distance,
estimated time, and a "back on route" hint when he strays. No turn-by-turn voice in v2.

## Rules

- The engine is whatever ticket 06 rules. **Not** a `MapboxNavigation` trip session for a hike
  (billing, battery); a route is requested, drawn and followed by the hike controller.
- `RouteRequest` gains a profile (today it is pinned to driving-traffic by
  `applyDefaultNavigationOptions()`, traced in `navigation/MapboxNavSdk.kt`). The driving path must
  not change.
- A Mapbox route is never stored (2.10.1). A BRouter track may be kept with the hike.
- No route found is said in words with the reason (no data here, no trail connects, too far);
  never a straight line pretending to be a route.
- If BRouter: vendored with its MIT licence text; its segment download is the second exception ruled
  in ticket 06; ODbL credit in About.

## Verification

- Unit: profile plumbing (driving unchanged), no-route sentences, route-following hint maths.
- Compile, suite green. On the phone in airplane mode: the spike's 5 pairs route. Owed.
