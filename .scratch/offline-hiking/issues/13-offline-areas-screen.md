---
map: offline-hiking
ticket: 13
title: "Build: offline areas (download, list, refresh, delete) and the download controller"
type: build
status: open
status-detail: ""
blockers: ["04", "05", "10", "11"]
blocked-by: ["[[04-downloaded-regions-vs-bundled-assets]]", "[[05-offline-map-style]]", "[[10-storage-and-refresh]]", "[[11-how-an-area-is-picked]]"]
open-blockers: 4
ready: false
tags: [ticket]
---
# Build: offline areas (download, list, refresh, delete) and the download controller

**Release:** v1. **Size:** M.

## What

An "Offline areas" screen, reached from the hike screen and the nav screen's menu:

- Frame an area on the map (ticket 11 option A), see its estimated size, name it, download.
- Progress with cancel. Partial failure said in words.
- The list: name, size on disk, downloaded date, "refresh due" when old, delete.
- Used / free against the quota (ticket 10).

## Rules

- One `OfflineAreasController` (injected class, Hilt), one `TileStore` instance for the process,
  the same one the Nav SDK uses (research 01; `MapboxOptions.mapsOptions.tileStore`).
- Download = `OfflineManager.loadStylePack` (Outdoors, once) + `TileStore.loadTileRegion` with the
  Outdoors descriptor (z10-16) plus `mapbox.mapbox-terrain-dem-v1` (ticket 05). Region metadata holds
  only Kevin's name and dates (ticket 11).
- **11.32 `PARTIAL_LOAD` is a failure in words**, never "downloaded" (§7 outcome verbs). Offer retry.
- "Downloaded" is said only after the completion callback with no error.
- No token: the screen says offline maps need the household's Mapbox token and points at Setup
  (ADR 0054's no-token rule).
- Mapbox and OpenStreetMap attribution on every map; "© OpenStreetMap contributors" also in About.
- Clone-and-run: a stranger's build with no token compiles and shows the no-token sentence.

## Verification

- Unit (fake TileStore seam): state machine for idle / downloading / partial / failed / done /
  cancelled; quota maths; the refresh-due rule; no-token path.
- Compile, suite green, detekt adds nothing new.
- On the phone: one real download on Wi-Fi, size recorded (answers research 01's MB per km² gap),
  airplane mode, map renders. Owed to ticket 19.
