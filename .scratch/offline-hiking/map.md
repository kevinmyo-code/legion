---
map: offline-hiking
title: Offline maps and hiking with no signal
charted: 2026-11-09
charted-by: "Opus, for Kevin"
effort: "`.scratch/offline-hiking/`"
tickets: 25
open: 22
status: open
tags: [map]
---
# Offline maps and hiking with no signal

**Kevin, 2026-11-09:** *"one of the uses id like is offline maps + hiking and topology basically we go
hiking mostly state parks but sometimes out in nowhere and no cell signal and no internet. so can we
have the current mapbox capabilities expand to help with that? offline, navigation, pathfinder type
shit?"*

Builds on `.scratch/mapbox-nav/` (ADR 0054) and `.scratch/mapbox-plus/`. **mapbox-plus ticket 05
(offline maps for driving areas) stays where it is**; this map covers hiking areas,
and its rulings on downloads (tickets 04, 10) should be reused there rather than re-argued.

## Destination

Every ruling below made, and v1 built and proven on one real state-park hike in airplane mode: an
offline topo map, his position and heading, a recorded track, the way back to the car, and the
light left before sunset. v2 (GPX plans, elevation profile, trail routing, offline voice, engine
sync) charted as tickets. Execution is in scope after the rulings.

## What exists (traced 2026-11-09, `origin/dev` 3afa3eca)

- Maps SDK 11.32 comes in through `com.mapbox.navigationcore:android-ndk27:3.32.0` (`ui-maps`);
  Search SDK 2.32.0. `ui/navigation/NavMap.kt` hosts a `MapView` in `AndroidView`, Standard style.
- One `MapboxNavigation`, owned by `navigation/MapboxNavSdk.kt`; routes requested with
  `applyDefaultNavigationOptions()` (driving-traffic). `RouteRequest` has no profile field.
- `navigation/resolve/DestinationResolver.kt`: saved places, calendar, contacts, then Mapbox search;
  search hits held in memory only (ToS 2.7.2).
- No `OfflineManager` or `TileStore` use anywhere.
- `navigation/MapLight.kt` computes sunrise and sunset offline. `service/WakeWordEngine.kt` runs Vosk
  with a runtime grammar offline. `navigation/voice/NavCueSpeaker.kt` speaks with Android TTS.
- `location/LocationController.kt` uses the platform `LocationManager` (GPS works with no signal).
- Places (label, lat, lng, address) live on the engine; body is the seventh aspect (ADR 0077).

## What works with no signal

| Layer | Offline | Note |
|---|---|---|
| Map and topo | Yes, once downloaded | Outdoors style: contours, hillshade, trails; DEM for 3D (research 01) |
| Position, heading, track | Yes | GPS and compass need no network |
| Routing | Not in v1 | Mapbox offline walking unconfirmed; BRouter is the fallback (research 02, ticket 06) |
| Search | No | Mapbox offline search is sales-gated; saved waypoints and an imported GPX only |
| Alfred's voice | **No** | Gemini Live needs the network. v2 option: fixed trail commands on Vosk + Android TTS (ticket 07) |
| Trail data | Yes, on the map | OSM paths exist in sampled parks; difficulty (`sac_scale`) and trailheads mostly untagged (research 03) |
| SOS | **No** | The A25 has no built-in satellite SOS (research 03). T-Mobile satellite text-to-911 eligibility unconfirmed: check the IMEI. A satellite messenger is the real answer |

## Notes

- Skills: `/grilling` for the decisions, `/research` is done (01-03), `/prototype` if the hike
  screen layout is contested (384dp, the A25).
- CLAUDE.md §7 binds every build ticket: outcome verbs only after a real result, a hands path for
  every voice tool, offline said in words, clone-and-run with no token.
- **No real locations in this repo.** Fixtures use made-up coordinates; spike and hike results with
  real places go to `.legion/reports/`.
- Hilt: injected controllers, one ViewModel per screen.
- A hike never starts a `MapboxNavigation` trip session (billing and battery).
- Mapbox Product Terms 1.3.4 forbids uses where failure could be expected to cause injury: the app
  says plainly that it is not a safety device.

## Tickets

| # | Type | What | Release | Size | Blocked by |
|---|---|---|---|---|---|
| 01 | research | Mapbox offline maps and topography | - | - | - |
| 02 | research | Offline trail routing on Android | - | - | - |
| 03 | research | Trail data, imports, phone sensors, safety | - | - | - |
| 04 | decision | Downloaded map areas against "assets are bundled" | - | - | 01 |
| 05 | decision | Offline map style: Outdoors, or Standard with topo added | - | - | 01 |
| 06 | decision | Offline trail routing: which engine, and when | - | - | 02, 22 |
| 07 | decision | Voice with no signal: fixed commands or nothing | - | - | - |
| 08 | decision | Where a hike lives among the seven aspects | - | - | - |
| 09 | decision | Trip plan before leaving signal, and "if not back by X" | - | - | 03 |
| 10 | decision | Storage budget, Wi-Fi only, refresh cadence | - | - | 01 |
| 11 | decision | How an offline area is picked and named | - | - | 01 |
| 12 | decision | Alfred's hiking tools while online | - | - | 11 |
| 13 | build | Offline areas screen and download controller | v1 | M | 04, 05, 10, 11 |
| 14 | build | Hike screen: offline map, GPS puck, compass | v1 | M | 05 |
| 15 | build | Track recorder, back to start, battery saver | v1 | M | 08, 14 |
| 16 | build | Waypoints with bearing and distance | v1 | S | 14 |
| 17 | build | Turnaround time against sunset | v1 | S | 15 |
| 18 | build | Share a trip plan before leaving signal | v1 | S | 09 |
| 19 | test | A real state-park hike on the A25 in airplane mode | v1 gate | - | 13-18 |
| 20 | build | GPX / KML import and off-trail warning | v2 | M | 14, 03 |
| 21 | build | Elevation profile | v2 | M | 15, 20 |
| 22 | task | Spike: Mapbox offline walking vs BRouter on the A25 | pre-v2 | S | - |
| 23 | build | Offline trail commands by voice | v2 | M | 07, 15, 16 |
| 24 | build | Hike sync to the engine, and the `hiking` tool | v2 | M | 08, 12 |
| 25 | build | Offline trail routing | v2 | M-L | 06 |

## Decisions for Kevin (each ticket has options and a pick)

| Ticket | Question | Pick |
|---|---|---|
| 04 | Second exception to "assets are bundled" | Yes, narrowly: Mapbox SDK offline areas, on his action, Wi-Fi by default |
| 05 | Style | Outdoors v12, DEM in the region, 3D off by default; Standard + own layers as the exit |
| 06 | Routing | None in v1; v2 after the spike: Mapbox walking if it follows trails, else embedded BRouter |
| 07 | Offline voice | None in v1; fixed trail commands (Vosk + TTS) in v2 |
| 08 | Where a hike lives | Body (a training session) + GPX file; car and trailhead as places |
| 09 | Safety sends | Manual trip-plan share + a reminder to himself in v1; engine "if not back by X" later, armed per hike, only with the stated terms |
| 10 | Storage | 1 GB quota, Wi-Fi only, prompt to refresh at 25 days, never auto-delete |
| 11 | Picking an area | "Download this view" by hand in v1; OSM park boundary for the voice path in v2 |
| 12 | Voice tools | One `hiking` tool with actions, after v1, sized to the payload headroom |

## Decisions so far

## Not yet specified

- Wife's phone: whether she records hikes too (ticket 08 owner column) and shares areas.
- Android Auto / head unit: nothing here touches either.
- Trail difficulty and closures: TPWD posts closures online; whether to show them pre-hike.
- Offline POIs (parking, water, toilets, campsites): pull a park's OSM POIs once while online and
  keep them with the area (ODbL, storable). Overpass's public server is flaky and discourages
  automated use; an engine job over the Geofabrik extract may be better. Graduates after ticket 13.
- TPWD's official trail KMZ as an optional overlay: needs its licence settled first (mixed wording).
- Watch or barometer: the A25 has no barometer; a watch could give better ascent later.

## Out of scope

- Handing a hike to OsmAnd, Organic Maps or Google Maps (ADR 0054).
- Satellite messaging from LEGION: the A25 has no built-in satellite radio (research 03); any
  carrier satellite text is the phone's own feature, not LEGION's.
- Bundling map tiles in the APK: Mapbox terms forbid it (research 01, 1.10).
