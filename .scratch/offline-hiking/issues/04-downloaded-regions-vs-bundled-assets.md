---
map: offline-hiking
ticket: 04
title: "Decision: downloaded map regions against \"assets are bundled\""
type: decision
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-mapbox-offline-facts]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Decision: downloaded map regions against "assets are bundled"

## Question

CLAUDE.md §7 says assets are bundled in `assets/` or `res/`, never fetched at runtime, with one
exception (ADR 0058, game box art). An offline map is a runtime download by definition: a park's
tiles cannot ship in the APK. Does Kevin grant a second exception, and on what terms?

## Options

| Option | What it means | Cost |
|---|---|---|
| A. No exception | No offline maps. The hike screen works only where the SDK's ambient cache happens to hold tiles. | Kills the feature. The ambient cache (ProductTerms 2.8.1 in `mapbox-nav` research) is not a plan: nothing says which tiles are in it. |
| B. Bundle a fixed region in the APK | Ship one region's tiles as an asset. | APK grows by the region's size, every build, for every clone; a new park is a new APK. Also a Mapbox ToS question (tiles copied outside the SDK). |
| **C. Exception: Mapbox offline regions, downloaded by the SDK, only on the user's own action** | `OfflineManager` / `TileStore` fetch a style pack and tile region when Kevin asks. The SDK owns the files. | One more stated exception. |
| D. Exception for any map data (OSM extracts, routing graphs, DEMs) | Broad. | Opens the door for ticket 06's routing data too, without a separate look. |

## Recommendation

**C, worded narrowly, with a named follow-on for routing data.**

- The exception covers map tiles and style packs fetched through the Mapbox Maps SDK's own offline
  API (`OfflineManager`, `TileStore`), stored where the SDK stores them, and nothing else.
- Only on the user's action: a button on the regions screen, or a voice request while online
  (ticket 12). Never a background prefetch guessed from the calendar or location.
- Wi-Fi only by default, with a per-download "use mobile data" override (ticket 10).
- Offline or not yet downloaded, the hike screen says so in words ("No offline map here. Download
  one before you lose signal.") and still shows the puck, the track and the waypoints on a blank
  grid, because GPS needs no network.
- Routing data (a BRouter segment, a GraphHopper graph) is NOT covered. If ticket 06 picks an engine
  that needs its own download, that is a second, separate ruling.
- Record it the way ADR 0058 recorded box art: an ADR (or an amendment to 0054) plus the one line in
  CLAUDE.md §7 ("Nothing else rides this exception" becomes a list of two).

## Why not B

A region baked into the APK grows every build, and a clone made by a stranger would ship Kevin's
choice of park. It also copies Mapbox tiles outside the SDK, which the terms do not obviously allow
(reasoned; research 01 has the clauses).
