---
map: offline-hiking
research: "02"
title: "Offline trail routing on Android"
tags: [research]
---

# Offline trail routing on Android

Checked 2026-11-09. `sourced` = stated on the linked page. `reasoned` = my inference from sourced facts, not stated anywhere.

## Bottom line

- Mapbox Nav SDK v3 does route offline (TileStore routing tiles, `RouterOrigin.OFFLINE`). Tiles accept a `walking` profile. No page says offline *walking* routing works. Needs a device spike.
- Mapbox `walking` = "sidewalks and trails". No public word on OSM tags or sac_scale. No difficulty control.
- Ranked on-device pick: 1) BRouter, 2) Valhalla via valhalla-mobile, 3) Mapbox offline walking (only if spike passes). GraphHopper last.

## 1. Mapbox Navigation SDK v3

| Fact | Tag | Source |
|---|---|---|
| Two offline modes: predictive caching (automatic) and offline regions loaded ahead. Offline regions allow routing and rerouting with no connection, no HTTP routing calls | sourced | https://docs.mapbox.com/android/navigation/guides/advanced/offline.md |
| Wiring: `TileStore.create(path)` -> `RoutingTilesOptions.Builder().tileStore(...)` -> `NavigationOptions.routingTilesOptions(...)`. Same TileStore must be shared with Maps | sourced | https://docs.mapbox.com/android/navigation/guides/advanced/offline.md |
| Region download: `tileStore.loadTileRegion(id, TileRegionLoadOptions{geometry, descriptors})`. Nav descriptor from `mapboxNavigation.getTilesetDescriptorFactory().getLatest()` | sourced | https://docs.mapbox.com/android/navigation/guides/advanced/offline.md |
| `TilesetDescriptorFactory.build(tilesDataset, tilesProfile, tilesVersion)`; null args fall back to `RoutingTilesOptions` values | sourced | https://docs.mapbox.com/android/navigation/api/coreframework/3.16.0-rc.1/navigation/com.mapbox.navigation.core.navigator/-tileset-descriptor-factory/ |
| `tilesProfile`: "One of (driving\|driving-traffic\|walking\|cycling\|truck)" | sourced | https://docs.mapbox.com/android/navigation/api/coreframework/3.16.0-rc.1/base/com.mapbox.navigation.base.options/-routing-tiles-options/ |
| `fallbackOfflineTilesVersion` used "in case an offline route can't be built with tilesVersion" | sourced | same as above |
| `RouterOrigin.Onboard` renamed `RouterOrigin.OFFLINE`; SDK auto-switches to online alternative when primary route is offline | sourced | https://docs.mapbox.com/android/navigation/releases/core |
| Offline guide names no supported profiles and no access gate | sourced (absence) | https://docs.mapbox.com/android/navigation/guides/advanced/offline.md |
| Offline walking routing likely possible: walking tiles exist as a profile option, router reads tiles. Unconfirmed; test with `build(tilesProfile = "walking")` region + `RouteOptions` profile walking in airplane mode | reasoned | (from rows above) |
| A 2018 Mapbox blog said offline routing would support walking, cycling, driving (not driving-traffic). Seen only in search snippet; page returned 403 | reasoned (weak) | https://blog.mapbox.com/stay-on-course-with-offline-routing-d8d8e3c49ee |
| App uses `applyDefaultNavigationOptions()` = driving-traffic. Offline needs a non-traffic tiles profile; default driving tiles won't give trail routes | reasoned | (from `tilesProfile` row) |
| Pricing page lists Route tiles API; requests outside Active Guidance / Free Drive are "not included" (billed separately). No enterprise/sales gate stated for offline routing | sourced | https://docs.mapbox.com/android/navigation/guides/pricing.md |
| Offline routing looks self-serve on a normal token | reasoned (no gate text found) | (rows above) |
| Predictive cache size examples: Atlanta 100 mi ~659 MB maps + ~156 MB nav | sourced | https://docs.mapbox.com/android/navigation/guides/advanced/offline.md |
| `mapbox/walking`: "For pedestrian and hiking routing. This profile shows the shortest path by using sidewalks and trails." | sourced | https://docs.mapbox.com/help/glossary/routing-profile.md |
| Directions API walking options: `walkway_bias` (-1..1), `walking_speed` (default 1.42 m/s). Walking max 1,000 km total, 25 waypoints | sourced | https://docs.mapbox.com/api/navigation/directions/ |
| No mention of OSM `highway=path/footway/track/bridleway` or `sac_scale` in Mapbox routing docs. No difficulty limit param | sourced (absence) | https://docs.mapbox.com/api/navigation/directions/ |
| Mapbox routing graph is OSM-derived (OSRM heritage, OSM road network refs). Trail coverage likely ~ OSM, but filtering rules unknown | reasoned | https://docs.mapbox.com/api/legacy/directions-v4/ |
| Offline Search SDK exists (`OfflineSearchEngine`, TileStore descriptors; addresses, streets, POIs, places) | sourced | https://docs.mapbox.com/android/search/guides/search-engine/offline.md |
| Offline Search is gated: "a premium feature. Contact our sales team to enable your Mapbox account" | sourced | https://docs.mapbox.com/android/search/guides/search-engine/offline.md |

## 2. BRouter

| Fact | Tag | Source |
|---|---|---|
| Licence MIT ("Copyright (c) 2019 BRouter contributors") | sourced | https://raw.githubusercontent.com/abrensch/brouter/master/LICENSE |
| Android app on F-Droid and Google Play; Locus Map and OsmAnd use it as offline engine | sourced | https://github.com/abrensch/brouter |
| F-Droid: `btools.routingapp`, MIT, v1.7.10, no anti-features | sourced | https://f-droid.org/packages/btools.routingapp/ |
| GitHub release v1.7.10 2026-07-17; repo pushed 2026-11-06 (active) | sourced | https://github.com/abrensch/brouter/releases |
| Service API: AIDL `IBRouterService.getTrackFromParams(Bundle)`. Keys: `lonlats`/`lats`+`lons`, `v` (motorcar\|bicycle\|foot), `fast`, `profile` (brf name), `remoteProfile`, `trackFormat` (kml\|gpx\|json), `turnInstructionFormat`, `maxRunningTime` (default 60 s), `engineMode`, nogos. Call off main thread | sourced | https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/aidl/btools/routingapp/IBRouterService.aidl |
| Service doc: package `btools.routingapp`; `extraParams=avoidsteps=0`; `engineMode=4` round trips (`roundTripDistance`) | sourced | https://raw.githubusercontent.com/abrensch/brouter/master/docs/developers/android_service.md |
| Service path needs BRouter app installed and segments downloaded inside its folder | reasoned | (doc implies, not stated) |
| Segments: "organised as 5*5 degree files", named by SW corner (e.g. W100_N30.rd5); weekly planet updates | sourced | https://github.com/abrensch/brouter ; https://brouter.de/brouter/ |
| Texas segment sizes (2026-11-10 listing, bytes): W110_N30 15.96M, W105_N25 11.50M, W105_N30 19.47M, W105_N35 21.88M, W100_N25 35.33M, W100_N30 64.29M, W100_N35 33.10M, W95_N25 3.07M, W95_N30 33.90M, W95_N35 57.03M | sourced | https://brouter.de/brouter/segments4/ |
| Whole Texas = those 10 squares (El Paso needs W110_N30) ~= 296 MB. One park region = 1 file, 3 to 64 MB | reasoned | (sum of above) |
| Profiles shipped: hiking-mountain, trekking, shortest, mtb, gravel, car-vario, fastbike, etc. No plain "walking" profile; shortest is `validForFoot` | sourced | https://github.com/abrensch/brouter/tree/master/misc/profiles2 |
| hiking-mountain.brf: `SAC_scale_limit 3`, `SAC_scale_preferred 1`, `SAC_access_penalty 9000`; demanding_mountain_hiking = 3; treats `highway=track\|road\|path\|footway` as track; bridleway costed separately | sourced | https://raw.githubusercontent.com/abrensch/brouter/master/misc/profiles2/hiking-mountain.brf |
| Profiles are editable text scripts; tune SAC limits, avoid roads, etc. | sourced | https://github.com/abrensch/brouter |
| Gradle modules: brouter-core, -mapaccess, -util, -expressions, -codec, -map-creator, -server, -routing-app | sourced | https://raw.githubusercontent.com/abrensch/brouter/master/settings.gradle |
| Code is plain Java, no extra deps by policy; runs as server and Android service | sourced | https://raw.githubusercontent.com/abrensch/brouter/master/docs/developers/developer_guide.md |
| Embedding core in our app feasible: vendor or composite-build core modules, call engine in-process. No Maven artifact found, so vendor + track releases | reasoned | (modules + Java above) |

## 3. GraphHopper

| Fact | Tag | Source |
|---|---|---|
| Licence Apache 2.0, chosen to allow embedding in closed-source products | sourced | https://github.com/graphhopper/graphhopper |
| Android/offline "no longer officially supported"; last Android APK in README is 1.0 | sourced | https://github.com/graphhopper/graphhopper |
| Current server needs JVM >= Java 25; latest line 11.x | sourced | https://github.com/graphhopper/graphhopper |
| Forum Feb 2025: offline dropped after 1.0; one user built as Android lib, last checked v5.3 | sourced | https://discuss.graphhopper.com/t/offlne-routing-on-android/9176 |
| 5.3 -> 11.x gap + Java 25 baseline = high risk on ART; pin old version or fight desugaring | reasoned | (rows above) |
| 1.0 Android doc: build graph on desktop from OSM, copy folder to device; "routes for areas of up to 500km^2 ... under 5s" with CH | sourced | https://github.com/graphhopper/graphhopper/blob/1.0/docs/android/index.md |
| `hike.json`: `max_hike_rating` 5, blocks `hike_rating > max`, boosts foot_network routes 1.5 to 1.7x | sourced | https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/resources/com/graphhopper/custom_models/hike.json |
| `foot.json` blocks `hike_rating >= 2` (mountain_hiking and up) | sourced | https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/resources/com/graphhopper/custom_models/foot.json |
| sac_scale map: hiking 1, mountain_hiking 2 ... difficult_alpine_hiking 6 | sourced | https://github.com/graphhopper/graphhopper/blob/master/core/src/main/java/com/graphhopper/routing/util/parsers/OSMHikeRatingParser.java |
| Server RAM rule: `-Xmx` ~ graph-cache size minus geometry; import needs much more | sourced | https://github.com/graphhopper/graphhopper/blob/master/docs/core/deploy.md |
| Texas graph size / phone RAM: no primary number found | gap | - |

## 4. Valhalla on mobile

| Fact | Tag | Source |
|---|---|---|
| Valhalla licence MIT | sourced | https://github.com/valhalla/valhalla/blob/master/COPYING |
| valhalla-mobile (Rallista) MIT; Maven `io.github.rallista:valhalla-mobile`; latest 0.6.4 (2026-10-06); pushed 2026-10-20; not archived | sourced | https://repo1.maven.org/maven2/io/github/rallista/valhalla-mobile/maven-metadata.xml ; https://github.com/Rallista/valhalla-mobile |
| Builds Valhalla 3.9.0 for arm64-v8a, armeabi-v7a, x86_64, x86 | sourced | https://github.com/Rallista/valhalla-mobile/blob/main/android/valhalla/build.gradle.kts |
| Routes "entirely against the tiles on the device"; no isochrone/optimized_route/locate | sourced | https://github.com/Rallista/valhalla-mobile |
| Tiles: `ValhallaConfigFactory.usingTileExtract(tar)` or `usingTilesDir(dir, elevationDir)`; offline configs fetch nothing; library does not add INTERNET permission | sourced | https://github.com/Rallista/valhalla-mobile/blob/main/android/valhalla/docs/Config.md |
| Tiles built off-device with `valhalla_build_tiles` (pip bindings expose CLI) | sourced | https://github.com/valhalla/valhalla |
| Pedestrian costing: `max_hiking_difficulty` 0..6, default 1, maps to OSM sac_scale; `use_tracks` default 0.5; `walkway_factor`, `sidewalk_factor` 1.0; `type` default foot; `max_distance` 100 km | sourced | https://valhalla.github.io/valhalla/api/route/api-reference/ |
| Default difficulty 1 = only `sac_scale=hiking`; set 2 to 3 for Texas rugged trails | reasoned | (row above) |
| Texas tile tar size: no primary number found | gap | - |

## 5. Other apps (call, don't embed)

| Fact | Tag | Source |
|---|---|---|
| OsmAnd code GPLv3; UI/artwork CC-BY-NC-ND 4.0; publishing OsmAnd UI code needs written permission | sourced | https://github.com/osmandapp/OsmAnd/blob/master/LICENSE |
| OsmAnd AIDL `IOsmAndAidlInterface`: `navigate(NavigateParams)`, `navigateGpx`, nav updates callback | sourced | https://github.com/osmandapp/OsmAnd/blob/master/OsmAnd-api/src/net/osmand/aidlapi/IOsmAndAidlInterface.aidl |
| OsmAnd URI API `osmand.api://navigate` with `profile` param | sourced | https://github.com/osmandapp/OsmAnd/blob/master/OsmAnd/src/net/osmand/plus/helpers/ExternalApiHelper.java |
| OsmAnd can use BRouter as engine | sourced | https://github.com/abrensch/brouter |
| Organic Maps repo licence file points to Apache-2.0 | sourced | https://github.com/organicmaps/organicmaps/blob/master/LICENSE |
| Organic Maps URL parser: `route` with `sll`, `dll`, `saddr`, `daddr`, `type` = vehicle\|pedestrian\|bicycle\|transit | sourced | https://github.com/organicmaps/organicmaps/blob/master/libs/map/mwm_url.cpp |
| Organic Maps api-android lib: show points, pick point; no routing in README | sourced | https://github.com/organicmaps/api-android |
| Both apps can be launched with a pedestrian route by intent; nav UI then lives in their app, not ours | reasoned | (rows above) |

## 6. OSM extracts and licence

| Fact | Tag | Source |
|---|---|---|
| Geofabrik `texas-latest.osm.pbf` 693 MB, data to 2026-11-08T21:21Z | sourced | https://download.geofabrik.de/north-america/us/texas.html |
| BBBike: custom area up to 24M km^2, PBF and other formats, 2 to 7 min, free for end users; commercial = paid pro | sourced | https://extract.bbbike.org/extract.html |
| Park-sized BBBike extract would be a few MB, ideal for Valhalla/GraphHopper builds per park | reasoned | - |
| ODbL attribution: name OpenStreetMap + ODbL, e.g. "© OpenStreetMap contributors" linked to osm.org/copyright | sourced | https://osmfoundation.org/wiki/Licence/Attribution_Guidelines |
| "Routing engines that use OpenStreetMap data must credit OpenStreetMap"; apps embedding one too; route instructions need none | sourced | https://osmfoundation.org/wiki/Licence/Attribution_Guidelines |
| Guideline "does not concern internal uses"; attribution only for public use | sourced | https://osmfoundation.org/wiki/Licence/Attribution_Guidelines |
| Personal app: add About-screen credit anyway (cheap, public repo, future sharing) | reasoned | - |
| sac_scale values hiking .. difficult_alpine_hiking | sourced | https://wiki.openstreetmap.org/wiki/Key:sac_scale |
| US trails often lack sac_scale; engines fall back to highway type, so path-following matters more than difficulty in Texas | reasoned | - |

## Comparison

| Option | Licence | Effort (reasoned) | Offline packaging & size | Trail quality | Notes |
|---|---|---|---|---|---|
| Mapbox Nav SDK offline (walking tiles) | Proprietary SDK, Mapbox ToS | S if walking offline works; already in app | TileStore regions via `loadTileRegion`; size per region unknown for walking | "sidewalks and trails"; no sac_scale knob; tag rules opaque | Unverified offline walking. Spike first. Keep same TileStore as Maps |
| BRouter (embed core) | MIT | M (vendor Java modules, segment download, GPX/JSON parse) | Prebuilt weekly rd5, 5x5 deg; Texas ~296 MB total, park 3 to 64 MB | Best: hiking-mountain uses sac_scale limit/pref, path/track/footway; editable brf | Active 2026. No Maven artifact |
| BRouter (AIDL to app) | MIT (separate app) | S | Same rd5, managed by BRouter app | Same | User installs BRouter app + downloads segments |
| Valhalla via valhalla-mobile | MIT | M to L (Maven dep easy; tile build pipeline off-device) | Self-built tar from PBF/BBBike; size unknown | Good: `max_hiking_difficulty` = sac_scale, `use_tracks`, `walkway_factor` | arm64 native; OSRM-shaped JSON; active 2026 |
| GraphHopper | Apache 2.0 | L (no Android support since 1.0; Java 25) | Self-built graph folder; size unknown | Good: hike.json `max_hike_rating`, foot_network boost | Highest maintenance risk |
| OsmAnd (intent/AIDL) | GPLv3 app (+CC-BY-NC-ND UI) | S | OsmAnd .obf maps in OsmAnd | Good pedestrian; can use BRouter | Hands user to OsmAnd UI |
| Organic Maps (URL) | Apache 2.0 | S | OM .mwm maps in OM | Pedestrian profile, OSM trails | `om://route?...&type=pedestrian`; hands off |

### Ranked on-device pick (reasoned)

1. **BRouter, embedded.** Only option with prebuilt, weekly, small offline data (no build pipeline). sac_scale-aware hiking profile out of box. MIT. Pure Java fits Kotlin app. Fallback: AIDL to BRouter app (S effort) for a first spike.
2. **Valhalla via valhalla-mobile.** Clean Maven dep, arm64, sac_scale knob. Cost: you own a tile build (Geofabrik/BBBike PBF -> `valhalla_build_tiles` -> tar).
3. **Mapbox offline walking.** Cheapest if it works, already integrated. Opaque trail rules, no difficulty knob. Run a 1-hour airplane-mode spike before ranking higher.
4. GraphHopper: unsupported on Android, Java 25 baseline.

Conflict: CLAUDE.md §3 / ADR 0054 says LEGION navigation "never hands a trip to another map app". OsmAnd and Organic Maps handoff breaks that and needs a ruling from Kevin. BRouter-by-AIDL only returns a track (LEGION still draws and guides), so it fits, but adds a second-app install. Embedded BRouter or Valhalla fit cleanly.

### Licence note (public repo)

- Only GPL in the set: OsmAnd (GPLv3). Calling it by intent/AIDL is IPC between separate apps, not linking; no viral effect on our code (reasoned). Never copy OsmAnd source or UI assets in.
- No AGPL found in any option.
- BRouter, Valhalla, valhalla-mobile: MIT. Keep copyright + licence text when vendoring.
- GraphHopper, Organic Maps: Apache 2.0. Keep NOTICE if embedding.
- OSM data (rd5, tiles, graphs): ODbL. Credit OpenStreetMap in-app. Don't commit Texas data blobs to the repo (size, and derivative-DB share-alike if published).

## Gaps

- No Mapbox doc confirms offline routing for `walking` (or which profiles have offline tiles). Needs device test.
- Mapbox walking: which OSM highway values/tags are routable, whether `sac_scale` or `access=no` trails are excluded. Not public.
- Mapbox walking tile region size per area: unknown.
- Mapbox 2018 offline-profile blog post not readable (403); only search snippet.
- Valhalla tar size for Texas or one park: no primary figure.
- GraphHopper graph size and phone RAM for a Texas extract: no primary figure.
- BRouter: no published Maven artifact found; embedding = vendoring. Exact in-process API entry class not checked.
- BRouter service: whether `getTrackFromParams` works with segments missing (likely error) not documented.
- Organic Maps `om://route` param docs: only source code read, no user-facing API doc found.
- Texas trail sac_scale coverage: no taginfo count pulled.
