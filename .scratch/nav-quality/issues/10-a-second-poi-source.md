---
map: nav-quality
ticket: "10"
title: "Decision: a second POI source on the engine"
type: decision
status: kiv
status-detail: "2026-11-06, Kevin: later (D5)"
blockers: ["01"]
blocked-by: ["[[01-baseline]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Decision: a second POI source on the engine

## Question

Mapbox's POI data is the ceiling of tickets 03, 04 and 08. Is a second source worth it?

| Source | Licence | Where it runs | Key at query time | Cost |
|---|---|---|---|---|
| Foursquare OS Places | Apache 2.0 (sourced) | engine: a regional extract in Postgres, refreshed monthly | none (a Places Portal token to download) | engine disk, a refresh job |
| Overture places | CDLA Permissive 2.0 / Apache 2.0 per source (sourced) | the same | none | the same |
| Foursquare Places API (hosted) | commercial terms; free tier unverified (aggregators conflict) | phone or engine | a new household key | per call above the free tier |
| Google Places | see ticket 11: closed | - | - | - |

An open dataset is storable, so it would also unblock names in recents (ticket 05) and offline
search. It has no live hours or ratings at Google's depth (reasoned).

## How to decide

Ticket 01's zero-result phrases are the test set. Load a Houston extract, replay those phrases
against it, and count how many it answers that Mapbox did not. Build only if that count matters.

## Proven by NAV_AUDIT

A new `poi_open` source in `tried=`, and the zero-result rate on the replayed baseline phrases.

## Parked (Kevin, 2026-11-06)

"Later" (decision D5). Not closed: ticket 01's zero-result phrases are still the test that reopens it.
