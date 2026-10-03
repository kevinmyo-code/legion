---
map: mapbox-nav
ticket: "10"
title: "Route and guidance on the nav screen"
type: build
status: open
status-detail: ""
blockers: ["03", "06", "07", "09"]
blocked-by: ["[[03-resolving-a-spoken-destination]]", "[[06-the-nav-screen]]", "[[07-trip-lifecycle]]", "[[09-sdk-in-the-build]]"]
open-blockers: 4
ready: false
tags: [ticket]
---

# Route and guidance on the nav screen

## Build

The nav screen from 06, with a ViewModel. Destination resolution per 03, route preview, active
guidance, arrival, cancel. Lifecycle per 07. Hands path only; voice is 11.

## Verification

- Unit: destination resolution order, trip session start/stop guard.
- On the phone: route to a saved place and to a searched place, cancel mid-trip, screen off
  mid-trip, arrive. No trip session left running afterwards (check the Mapbox dashboard's trip count).
