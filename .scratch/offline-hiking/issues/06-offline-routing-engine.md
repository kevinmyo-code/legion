---
map: offline-hiking
ticket: 06
title: "Decision: offline trail routing (\"pathfinder\"), which engine, and when"
type: decision
status: open
status-detail: ""
blockers: ["02", "22"]
blocked-by: ["[[02-offline-routing-facts]]", "[[22-routing-spike]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Decision: offline trail routing ("pathfinder"), which engine, and when

## Question

Kevin asked for "navigation, pathfinder type shit". With no signal, what computes a route along
trails from here to a trailhead, a waypoint, or a loop? And is it in v1?

## What v1 already gives without a router

- The trails drawn on the Outdoors map (ticket 05).
- Back to start along his own recorded track (ticket 15): the most reliable way back, because it is
  where he actually walked.
- Bearing and distance to the car or any waypoint (ticket 16).
- A planned GPX line to follow, with an off-trail warning (ticket 20, v2 first item).

Off trail in open country, no router helps: there is no network of paths to route on. Bearing,
contours and the track are the tools there.

## Options for v2 (research 02)

| Option | Fit | Trail quality | Data and download | Effort |
|---|---|---|---|---|
| A. Mapbox offline `walking` | Same SDK, same TileStore, same download exception (ticket 04) | "Sidewalks and trails"; tag rules unknown; no difficulty knob | Routing tiles in the same region | S **if** the spike passes; unknown today |
| **B. BRouter, embedded (vendored MIT Java core)** | In-process; LEGION draws and guides | Best: `hiking-mountain` uses `sac_scale`, follows path/track/footway, profile is an editable script | Weekly `rd5` segment per 5x5 degree square, 3 to 64 MB; a **second download exception** (brouter.de) | M |
| C. BRouter app over AIDL | LEGION calls the installed BRouter app, gets a GPX back, draws it | Same as B | BRouter app manages its own segments | S, but a second app to install; fine for the spike |
| D. Valhalla (valhalla-mobile) | Maven dep, arm64 | Good (`max_hiking_difficulty`) | LEGION builds tiles itself (engine job from an OSM extract) | M-L |
| E. Hand off to OsmAnd / Organic Maps | Their app navigates | Good | Their maps | S; **breaks ADR 0054** ("never hands a trip to another map app") |
| F. No routing | v1 tools only | - | - | 0 |

## Recommendation

**F in v1. In v2: run the spike (ticket 22) first, then A if Mapbox walking follows trails offline,
else B.** A costs nothing new if it works. B is the best trail router and its data is small and
prebuilt, but it needs its own download exception and a vendored engine. E stays out (ADR 0054).

Mapbox terms to keep: a route result is never stored (2.10.1), so an offline Mapbox route is
recomputed, never saved. A BRouter or GPX track is not a Mapbox result and may be kept.

## Kevin must say

- Routing in v1: no (recommended) or yes.
- If the spike fails Mapbox: allow the BRouter segment download (a second exception to "assets are
  bundled")?
