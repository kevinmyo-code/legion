---
map: mapbox-nav
ticket: "01"
title: "What the Mapbox Nav SDK v3 actually offers a phone app"
type: research
status: resolved
status-detail: "Research landed 2026-10-03"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# What the Mapbox Nav SDK v3 actually offers a phone app

## Question

Which facts about Nav SDK v3 on Android could block or shape this map? Build requirements (is a
secret download token still needed?), runtime token, the programmatic surface a voice tool can
drive, spoken instructions and audio focus, trip lifecycle and billing, ToS on caching, and what
the Midnight AI predecessor learned.

## Status

Research agent dispatched 2026-10-03. Output: `research/01-sdk-facts.md`.

## Answer (2026-10-03)

Full detail, sourced and tagged: [research/01-sdk-facts.md](../research/01-sdk-facts.md).

- **No secret token to build.** Core Nav SDK v3 (`com.mapbox.navigationcore:android-ndk27:3.32.0`)
  and Maps, Search and maps-compose download anonymously (tested with curl; Gradle not run).
  Only the preview UX Framework needs one. Ticket 02 is close to moot.
- **Size:** about +56 MB uncompressed for arm64 (tested). `abiFilters` needed.
- **Views, not Compose, for nav widgets.** Drop-In UI is gone in v3. Maps has a Compose extension.
- **Runtime token works** (`MapboxOptions.accessToken`, sourced). A bad token answers HTTP 200 with
  `TokenInvalid` (tested).
- **Card matters.** With no card: 20 Nav users and 100 guided trips a month, and hitting a cap cuts off every Mapbox
  API. With a card: 100 users and 1,000 trips (sourced).
- **Every voice intent has a public API.** Adding a stop mid-trip means rebuilding the route, which
  starts a new billed trip. Place names and "nearest X" need the Search Box API (Geocoding no longer
  returns POIs).
- **Audio:** Mapbox is silent unless its player is wired. The instruction text and SSML arrive via
  `VoiceInstructionsObserver`, so LEGION can own the speech (feeds 05).
- **ToS:** 1.2.2 "vehicle usage" may require a paid licence (new ticket 13). 2.9.1 says the mobile
  SDK must be the only way the app reaches Mapbox, so search goes through the Search SDK or the
  server, never raw REST from the phone. 2.7.2 / 2.10.1: Mapbox results may not be stored, so a
  saved place cannot be a stored Mapbox geocode unless it uses permanent geocoding.
- **The SDK runs its own location foreground service** during a trip (tested). Starting with the
  screen off needs proving on the A25 (07).
