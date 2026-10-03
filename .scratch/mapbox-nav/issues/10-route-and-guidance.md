---
map: mapbox-nav
ticket: "10"
title: "Route and guidance on the nav screen"
type: build
status: built
status-detail: "Built 2026-10-03, suite green bar a known Room race; owes a run on the phone (list in Built)"
blockers: ["03", "06", "07", "09"]
blocked-by: ["[[03-resolving-a-spoken-destination]]", "[[06-the-nav-screen]]", "[[07-trip-lifecycle]]", "[[09-sdk-in-the-build]]"]
open-blockers: 1
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

## Built (2026-10-03)

**Controller, outlives the screen (07).** `navigation/MapboxNavController.kt` is one app-owned
instance (`MidnightApplication.navController`, beside `mapboxTokens`), constructed over a seam
(`navigation/NavSdk.kt`) so every rule is unit-tested against a fake. `MapboxNavSdk.kt` is the one
real seam; `NavMapFeed` hands the map the raw routes and progress. A trip ends only on arrival,
`end()`, a token change or process death; `onScreenLeft()` never ends GUIDING. Billing guard
(`NavTripGuard`): session only in GUIDING, started only after set routes, stopped BEFORE routes are
cleared, instance destroyed on every way out. No free drive.

**API = the four tools' verbs (04).** `preview`, `navigate(previewOnly)`, `start`, `addStop`,
`removeStop`, `setAvoid` / `toggleAvoid`, `takeAlternative`, `setMuted` (state only), `overview` /
`recenter`, `end`, `status()`. Every mutating call returns `NavResult(ok, message)` with `ok` read
from the SDK AFTER the call (`activeRoutes()`, `isSessionRunning()`); a refused change says the trip
is unchanged; `status()` with no trip is `NotNavigating`, and unknowns are null.

**Destination resolver (03).** `navigation/resolve/`: saved places, calendar, contacts, then Mapbox
search through the Search SDK 2.32 (`mapbox-search-android-ndk27`, anonymous, +1 dependency, no
conflict; no REST). `createSearchEngine` (not the built-in-providers form, which keeps a local
history) so nothing from Mapbox is stored. Unreadable and empty are different sentences for each
source. Ambiguity flag drives the read-back card.

**Screen: layout A (06).** `ui/navigation/` (`NavScreen`, `NavSheets`, `NavPanels`, `NavBanner`,
`NavMap`, `NavViewModel`, `NavActions`); replaces the spike screen and its diagnostics row. Route
`navigate` (optional `place` argument, `LegionRoute.NAVIGATE_PATTERN`). Entry points: the Fleet
screen's Cars panel has a **Navigate** row (the least invasive existing place: the Fleet tile already
carries the car and the saved places), and every saved place's **Navigate** action opens the screen on
that place. A typed destination field is the hands path for `navigate`. Both reach the screen through
`LocalNavEntryPoints` (provided in `MainActivity` around the two Fleet destinations) rather than a
parameter threaded through Fleet's baselined signatures. States: not set up / token refused,
choosing, several-places-match, preview (3 routes, alternative dashed), guiding (banner, Then strip,
muted tag, sheet, four tiles, stops and routes panels), rerouting, a reroute that found nothing,
arrived, ended, route failure, offline. The assistant "pill" of the prototype is not drawn: the
shell's talk bar already sits under every screen.

**Map fixes from the spike.** Route line drawn after the style loads and redrawn on every style
reload; location puck on; camera put on the fix, follows while guiding, frames the route in preview
and overview, with a floating overview / recenter button; `MapView` built on an AppCompat-themed
context.

### Readings and calls to check (nothing here was asked of Kevin)

- **Ticket 03 "first match that is unambiguous wins" is read as: the first source that MATCHES
  decides.** Two saved places named like "gym" ask which, rather than silently falling through to a
  stranger's gym from search. One line (`DestinationResolver.resolve`) if the other reading was meant.
- **Hands path previews first** (routes and Start), where voice `navigate` starts at once.
- `navigate` / `preview` while a trip is guiding is refused ("Already navigating to X. End that trip
  first."); changing the trip is `addStop` / `setAvoid` / `takeAlternative`.
- A `via` along the route only honours the route for a CATEGORY search (the SDK marks along-route
  unsupported for forward text search); a `via` that is not a category degrades to proximity-biased.
- A contact with no address is "said in words" only when the phrase is the contact's whole name; a
  partial name match with no address ("Target Pharmacy") does not block a search for the business.

### Verification accounted for (L11)

- Unit: resolution order, ambiguity, per-source unreadable-vs-empty wording, the controller's result
  mapping against a fake SDK, the trip guard across arrival / End / screen-left-while-guiding / late
  responses, status "not navigating" and unknowns: **done** (`app/src/test/.../navigation/`).
- Screens rendered with Roborazzi at 384 x 832 (`NavScreenScreenshotTest`, baselines in
  `app/src/test/snapshots/nav-*.png`): **done**; the map is a stand-in there, so the route line,
  puck and camera are **deferred to the phone**.
- On the phone, **deferred to ticket 12 (a real drive) or an earlier walk**: route from a saved place;
  typed search; "nearest gas station"; alternatives; no tolls; add and drop a stop; overview and
  recenter; screen off mid-trip; leave the screen and come back mid-trip; arrival; End; the Mapbox
  dashboard's trip count after a few runs (no session left running); the route line, puck and camera
  follow on the real map; the `ThemeUtils` log noise gone; the along-route category search.
