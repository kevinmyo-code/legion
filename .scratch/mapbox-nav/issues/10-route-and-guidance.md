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

## Spike findings to carry in (A25, 2026-10-03)

The spike (`feat/mapbox-nav`, `navigation/MapboxNavController.kt`, `ui/navigation/NavSpikeScreen.kt`)
ran on the phone:

- **on-device:** tiles render; a route came back in about 0.4 s from the live fix to the saved `home`
  place; guidance text populated ("Turn left", "116 mi left, 1 h 59 min"); Stop and backing out both
  stop the trip session, and the SDK's `NavigationNotificationService` and its notification are gone
  afterwards. No crash.
- **on-device, a gap:** no route line and no location puck in view. The camera is fixed on downtown
  Houston and never follows or fits the route. `NavSpikeScreen` also skips drawing the route line if
  the style has not loaded yet and never retries (reasoned cause). This build needs camera follow,
  fit-to-route in preview, the puck, and a route line drawn after the style loads.
- **on-device, open:** the SDK starts a Free Drive session for about 8 ms before Active Guidance
  inside the same call. Mapbox's 30 s grace period should keep it unbilled (reasoned); check the
  dashboard's trip count after a few test runs.
- **on-device, noise:** `ThemeUtils` wants an AppCompat theme for the compass, logo and attribution
  views under a Compose-hosted `MapView`; the map still renders. Fix with a themed context.
