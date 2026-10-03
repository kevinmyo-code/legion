---
map: mapbox-nav
title: "Native Mapbox navigation, controlled by voice"
charted: 2026-10-03
charted-by: "Kevin + Opus"
effort: "`.scratch/mapbox-nav/`"
tickets: 13
open: 10
status: open
tags: [map]
---

# Native Mapbox navigation, controlled by voice

**Kevin, 2026-10-03:** *"full mapbox native. i want max voice functionality"*. ADR 0054.

## Destination

**Shipped, and verified on a drive.** The assistant can start, change, question and end a trip by
voice: where to, which way, add or drop a stop, avoid tolls or highways, how long left, what is the
next turn, what is the traffic like, cancel. Every one of those also works by hand on the nav screen.
The Google Maps hand-off is gone. Execution is in scope.

## What exists, so nothing here is invented (traced 2026-10-03)

- `location/NavigationController.kt`: fires `google.navigation:` / `geo:` intents and returns an
  `Outcome` derived from whether `startActivity` ran. Pure parts tested in `NavigationControllerTest`.
- `open_navigation` in `service/LiveToolbox.kt` calls it. The Fleet screen's navigate icon is the
  only hands path. Verified on the phone 2026-08-19 (`.scratch/drive-test-2026-08-18/` ticket 03).
- `AndroidManifest.xml` `<queries>` carries `google.navigation` and `geo` for it.
- Saved places: `tag_place` / `forget_place` / `show_saved_places`. GPS spots with labels, never
  read by `open_navigation` today. `get_current_location` uses Android's `Geocoder`.
- No Mapbox dependency in the build. Midnight AI had Nav v3 + Mapbox geocoding (`NavGeocoder`); it
  was not ported. History in `memory/library/backlog-nav.md` (FROZEN) and `decisions.md` 2026-07-08
  and 2026-07-25.

## Rulings at charting (Kevin, 2026-10-03)

- Mapbox only. No Google fallback. No token means no navigation, said in words.
- The Google hand-off is removed in the change that ships Mapbox, never earlier.
- Phone only. Android Auto is fog.
- Free tier covers two users. A trip session runs only while navigating.

## Notes

- Skills: `/grilling` for decisions, `/research` for 01, `/prototype` for 06
  (clickable HTML, 384dp for the A25, per Kevin's standing preference).
- **CLAUDE.md §7 binds every build ticket:** outcome verbs only after a successful result, a hands
  path for every voice tool calling the same controller, no-token and offline said in words,
  clone-and-run by a stranger.
- **Hilt rules apply:** the new controller is an injected class, never an `object`; the nav screen
  gets a ViewModel exposing one `StateFlow<UiState>`.
- Gradle: one writer at a time in this tree.

## The tickets

| # | Type | What | Blocked by |
|---|---|---|---|
| 01 | research | What the Mapbox Nav SDK v3 actually offers a phone app | - |
| 02 | decision | Building with and without Mapbox credentials | 01 |
| 03 | decision | Resolving a spoken destination | 01 |
| 04 | decision | The voice tool surface and its honesty contract | 01 |
| 05 | decision | Who speaks the turns, and audio beside a live mic | 01 |
| 06 | prototype | The nav screen | 04 |
| 07 | decision | Trip lifecycle: screen off, backgrounded, billed | 01 |
| 08 | decision | Where the Mapbox token lives | 01 |
| 09 | build | Mapbox in the build, behind the clone-and-run gate, with token setup | 02, 08, 13 |
| 10 | build | Route and guidance on the nav screen | 03, 06, 07, 09 |
| 11 | build | Voice tools over the live trip, and the Google hand-off retired | 04, 05, 10 |
| 12 | test | A real drive on the A25 | 11 |
| 13 | decision | Does Mapbox's vehicle-usage clause cover a phone app | - |

**Order.** A spike on `feat/mapbox-nav` proves the SDK builds and guides on the A25, which closes 02.
Then 05 (who speaks the turns) and 04; 03 and 07 after.

## Decisions so far

- [What the Mapbox Nav SDK v3 actually offers a phone app](issues/01-sdk-facts.md) - no secret token to
  build, +56 MB arm64, every voice intent has an API, LEGION can own the spoken turns, a card lifts
  the trip cap from 100 to 1,000, and ToS 1.2.2 needs an answer from Mapbox.
- [Does Mapbox's vehicle-usage clause cover a phone app](issues/13-vehicle-usage-clause.md) - Kevin reads it as
  not covering a personal phone app and accepts the risk; no email.
- [Where the Mapbox token lives](issues/08-where-the-token-lives.md) - per phone in `KeyVault` via Setup; a dev
  token baked from the `MAPBOX_ACCESS_TOKEN` Gradle property; no token is said in words.
- [Building with and without Mapbox credentials](issues/02-build-with-and-without-credentials.md) - no gate needed:
  the SDK resolves anonymously through Gradle and compiles on the current toolchain (built).

## Not yet specified

- **Android Auto.** Mapbox has an Android Auto extension; `.scratch/android-auto/` owns the car's
  screen and mic and has its own open decisions.
- **Proactive raises during a trip** (an incident ahead, fuel low from OBD). Each must pass the §7
  compulsion test; likely its own small map once tools exist.
- **Trips as data.** Whether a finished trip becomes a fleet drive or a places arrival
  (`.scratch/place-arrivals/`). Not needed to navigate.
- **A map outside navigation**: saved places on a map, a trip history map.
- **Offline maps** for dead zones.
- **TomTom may be redundant now.** `app/build.gradle.kts` bakes a `TOMTOM_API_KEY` for traffic-aware
  ETA (hands-and-senses ticket 14, picked as the only no-card routing vendor), and almost no code uses
  it. Mapbox's traffic-aware routing covers the same question. Keep, retire, or keep only for ETA
  with no trip running: a ruling for Kevin once 04 lands.
- **Custom map styles.** Built with Mapbox's DevKit MCP server (`@mapbox/mcp-devkit-server`) from
  Claude Code, on Kevin's secret token, loaded in the app by style URL on the public token. Feeds 06.

## Out of scope

- **Any hand-off to Google Maps or Waze.** Ruled at charting.
- **A Mapbox token Kevin holds for anyone else.** BYO per household (§7).
- **Other map SDKs** (Google Nav SDK, MapLibre, Stadia). Not reopened.
