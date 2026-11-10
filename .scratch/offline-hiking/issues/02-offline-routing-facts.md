---
map: offline-hiking
ticket: 02
title: "Research: offline trail routing on Android"
type: research
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Research: offline trail routing on Android

## Question

Does the Mapbox Navigation SDK route offline, on which profiles, and does walking follow OSM
trails? If not, which on-device router fits LEGION: GraphHopper, BRouter, Valhalla, or an app hand-off?

## Answer

Findings: [research/02-offline-routing.md](../research/02-offline-routing.md) (2026-11-09).

- Mapbox routes offline from routing tiles in the TileStore (`RouterOrigin.OFFLINE`). Tiles take a
  `walking` profile, but no page says offline walking routing works: **unconfirmed, needs a spike**.
  `walking` is "sidewalks and trails"; no OSM tag list, no `sac_scale`, no difficulty knob.
- LEGION requests `driving-traffic` today (`applyDefaultNavigationOptions()`); that will never
  give a trail route.
- Mapbox offline search is a premium, sales-gated feature: out.
- BRouter: MIT, active, weekly prebuilt `rd5` segments (5x5 degrees; one Texas square 3 to 64 MB,
  all Texas about 296 MB), `hiking-mountain` profile honours `sac_scale`. AIDL service for a spike;
  plain Java core can be vendored (no Maven artifact).
- Valhalla (valhalla-mobile, MIT, Maven, arm64): good `max_hiking_difficulty`, but LEGION would
  own a tile build pipeline.
- GraphHopper: Android unsupported since 1.0. Last.
- OsmAnd / Organic Maps by intent: easy, but a hand-off to another map app, which ADR 0054 forbids.
