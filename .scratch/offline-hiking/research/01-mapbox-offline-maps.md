---
map: offline-hiking
research: "01"
title: "Mapbox offline maps and topography"
tags: [research]
---

# Mapbox offline maps and topography

Scope: Maps SDK for Android v11 (app is on 11.32 via Nav SDK `android-ndk27:3.32.0`). Checked 2026-11-09.
Tags: `sourced` = the doc says it. `reasoned` = my inference.

Main sources:
- Concepts: https://docs.mapbox.com/android/maps/guides/offline/concepts/
- Manage data: https://docs.mapbox.com/android/maps/guides/offline/manage-offline-data/
- Changelog: https://github.com/mapbox/mapbox-maps-android/blob/main/CHANGELOG.md (top entry 11.35.0-rc.1, 2026-11-02)
- Product Terms PDF, "Last Updated: October 20, 2026", linked from https://www.mapbox.com/legal/product-terms

## 1. Maps SDK v11 offline APIs

| Fact | Tag | Source URL |
|---|---|---|
| Offline needs **both** a style pack (style JSON, sprites, fonts, 3D models) **and** a tile region (tile packs). | sourced | https://docs.mapbox.com/android/maps/guides/offline/concepts/ |
| Style pack is "typically only a few megabytes" and is downloaded once per style. All regions using that style share it. | sourced | https://docs.mapbox.com/android/maps/guides/offline/concepts/ |
| `OfflineManager` manages style packs and creates `TilesetDescriptor`s. `TileStore` downloads and stores tile regions. | sourced | https://docs.mapbox.com/android/maps/guides/offline/concepts/ |
| Descriptor: `TilesetDescriptorOptions.Builder().styleURI(..).pixelRatio(..).minZoom(..).maxZoom(..)`, then `offlineManager.createTilesetDescriptor(options)`. | sourced | https://docs.mapbox.com/android/maps/guides/offline/manage-offline-data/ |
| To add extra tilesets (e.g. DEM), use `TilesetDescriptorOptions.Builder.tilesets`. `createTilesetDescriptor(tilesetDescriptorOptionsForTilesets)` was removed in v11. | sourced | https://docs.mapbox.com/android/maps/guides/migrate-to-v11/ |
| Style download: `offlineManager.loadStylePack(styleUri, StylePackLoadOptions, progressCb, resultCb)`. Options take `glyphsRasterizationMode`, `metadata`, `acceptExpired`. Returns a `Cancelable`. | sourced | https://docs.mapbox.com/android/maps/guides/offline/manage-offline-data/ |
| Region download: `tileStore.loadTileRegion(id, TileRegionLoadOptions(geometry, descriptors, metadata, acceptExpired, networkRestriction), progressCb, resultCb)`. Returns a `Cancelable`. | sourced | https://docs.mapbox.com/android/maps/guides/offline/manage-offline-data/ |
| Progress callbacks: `TileRegionLoadProgressCallback` and `StylePackLoadProgressCallback`. Cancel with `cancelable.cancel()`. | sourced | https://docs.mapbox.com/android/maps/guides/offline/manage-offline-data/ |
| List: `offlineManager.getAllStylePacks{}`, `tileStore.getAllTileRegions{}`. Delete: `offlineManager.removeStylePack(uri)`, `tileStore.removeTileRegion(id)`. | sourced | https://docs.mapbox.com/android/maps/guides/offline/manage-offline-data/ |
| `removeTileRegion` "may not immediately delete the downloaded tile packs". To fully remove tiles, set the disk quota to zero. | sourced | https://docs.mapbox.com/android/maps/guides/offline/manage-offline-data/ |
| Update = call `loadStylePack` / `loadTileRegion` again with the same ID and `acceptExpired=false`. "Missing resources will be loaded and expired resources will be updated." | sourced | https://docs.mapbox.com/android/maps/guides/offline/manage-offline-data/ |
| No automatic updates: "you must manually refresh style packs and tile regions". | sourced | https://docs.mapbox.com/android/maps/guides/offline/ |
| The style pack completion callback exposes an expiration date. **No numeric expiry period (days) is documented.** | sourced | https://docs.mapbox.com/android/maps/guides/offline/manage-offline-data/ |
| **Limit: "The cumulative number of unique tile packs used in the tile regions cannot be greater than 750."** The SDK refuses a region load that would exceed it. Limit counts tile packs, not regions or tiles. | sourced | https://docs.mapbox.com/android/maps/guides/offline/concepts/ |
| The 750 limit is per on-device tile store, so per device/install, not per account. Since 11.24.1 there is only one TileStore per process. | reasoned | https://github.com/mapbox/mapbox-maps-android/blob/main/CHANGELOG.md (11.24.1) |
| No doc says whether 750 can be raised. | reasoned (absence) | https://docs.mapbox.com/help/dive-deeper/mobile-offline/ |
| The "6,000 tiles per end user" ceiling and `setOfflineMapboxTileCountLimit` are **legacy SDK (v8.3 to v9) only**, not v11 TileStore. | sourced | https://docs.mapbox.com/android/legacy/maps/guides/offline/ |
| Tile store quota: "By default, there is no limit to the size of a tile store." It is settable: `tileStore.setOption(TileStoreOptions.DISK_QUOTA, Value.valueOf(bytes))`. Unit is bytes. | sourced | https://docs.mapbox.com/android/maps/guides/offline/concepts/ , https://docs.mapbox.com/android/maps/api/11.5.0/mapbox-maps-android/com.mapbox.common/-tile-store-options/ |
| Over quota: the store "will refuse to store new data". Eviction starts at 50% of the quota. Packs used by a region are not evicted, so the store "may keep growing". | sourced | https://docs.mapbox.com/android/maps/guides/offline/concepts/ |
| **Conflict:** concepts doc says eviction picks the nearest expiration date first. Changelog 11.23.0 says the default is now LRU. Trust the changelog for 11.32. Eviction threshold also conflicts: 11.2.0 says "50Mb (or 10% of DiskQuota, whatever is smaller)" before the quota, while concepts says 50% of the quota. | sourced (both) | https://github.com/mapbox/mapbox-maps-android/blob/main/CHANGELOG.md (11.23.0) |
| The ambient cache is separate from the tile store ("does not share tiles"). It defaults to a 1 GB quota (11.24.1), configurable via a TileStore option (11.26.0-rc.1). `tileStore.clearAmbientCache()` exists. The docs use "disk cache" and "ambient cache" loosely; I did not confirm which one the 1 GB applies to. | sourced (reasoned on naming) | https://github.com/mapbox/mapbox-maps-android/blob/main/CHANGELOG.md |
| **11.32 breaking change:** `loadTileRegion()` now fails with `TileRegionErrorType.PARTIAL_LOAD` when some resources failed. It used to report success. | sourced | https://github.com/mapbox/mapbox-maps-android/blob/main/CHANGELOG.md (11.32.0-rc.1) |
| `TileStore.create(path)` was removed in 11.24.1. Use `MapboxOptions.mapsOptions.tileStore`, as the example does. | sourced | https://github.com/mapbox/mapbox-maps-android/blob/main/CHANGELOG.md , https://docs.mapbox.com/android/maps/examples/android-view/offline-map/ |
| Nav SDK: pass the **same TileStore instance** to Maps and to Nav `RoutingTilesOptions`. Nav tiles go into the same region via `tilesetDescriptorFactory.getLatest()`. | sourced | https://docs.mapbox.com/android/navigation/guides/advanced/offline/ |
| Only Mapbox-hosted tilesets work offline. Supported URL schemas: Vector/Raster tile v4 and **Raster DEM tile v1**. | sourced | https://docs.mapbox.com/android/maps/guides/offline/concepts/ |
| The map reads the tile store but won't fetch new packs over the network unless `tileStoreUsageMode` is `READ_AND_UPDATE`. | sourced | https://docs.mapbox.com/android/maps/guides/offline/manage-offline-data/ |

## 2. Size at hiking zooms

| Fact | Tag | Source URL |
|---|---|---|
| Tile pack scheme (index/min/max zoom, tiles per pack): global 0/0-5 (~1.4K), state 6/6-10 (~341), town 11/11-14 (~85), building 12/15-16 (~320). | sourced | https://docs.mapbox.com/android/maps/guides/offline/concepts/ |
| Zooms snap to pack ranges: "minZoom 8 and maxZoom 15 ... will include zoom levels ranging from 6 to 16". | sourced | https://docs.mapbox.com/android/maps/guides/offline/concepts/ |
| So a z10-16 request downloads packs at index 6 (z6-10), 11 (z11-14) and 12 (z15-16). A minZoom of 5 or less also pulls the ~1.4K-tile global pack. | reasoned | https://docs.mapbox.com/android/maps/guides/offline/concepts/ |
| Published sizes: style pack is "a few megabytes"; tile packs are "tens to hundreds of megabytes". **No bytes-per-tile or bytes-per-km² figure is published.** | sourced | https://docs.mapbox.com/android/maps/guides/offline/concepts/ |
| Pre-download estimate: `TileStore.estimateTileRegion(id, TileRegionLoadOptions, ...)`: "Estimates the storage and transfer size of a tile region." It was added in 11.3.0 and is present in the 11.32.0 API reference. The help page claiming there is "no mechanism" is stale. After download, use `TileRegion.completedResourceSize`. | sourced | https://docs.mapbox.com/android/maps/api/11.32.0/mapbox-maps-android/com.mapbox.common/-tile-store/ , https://docs.mapbox.com/help/troubleshooting/estimating-offline-usage/ |

Tile counts for a 10x10 km box at ~30°N. All `reasoned`: I computed them with Web Mercator math over 2000 random box offsets. Tile width is about 40,075 km / 2^z x cos(30°).

| Zoom | Tile width at 30°N | Intersecting tiles (min / median / max) |
|---|---|---|
| 10 | 33.9 km | 1 / 2 / 4 |
| 11 | 16.9 km | 1 / 2 / 4 |
| 12 | 8.5 km | 4 / 4 / 9 |
| 13 | 4.2 km | 9 / 12 / 16 |
| 14 | 2.1 km | 25 / 36 / 36 |
| 15 | 1.06 km | 100 / 110 / 121 |
| 16 | 0.53 km | 361 / 400 / 400 |
| **Sum z10-16** | | **501 / 566 / 590** |

Packs and downloaded tiles. All `reasoned`: packs download their whole footprint, not just the tiles that intersect the box.

| Item | Value (median case) |
|---|---|
| Packs per tileset, z10-16, 10x10 km | 1 (idx 6) + 2 (idx 11) + 4 (idx 12) = **7 packs** (range ~6-15) |
| Tiles actually downloaded per tileset | 341 + 170 + 1,280 ≈ **1,800** (vs 566 intersecting) |
| Large-region density, per tileset to z16 | ≈ 4.8 tiles/km² (z12 footprint ≈ 71.8 km² holds 320 z15-16 tiles) |
| Area that hits 750 packs, 1 tileset to z16 | ≈ 43,000 km² (~208 km square). With 2 tilesets ≈ 21,500 km² |
| A state park, say 50-500 km², z10-16 | roughly 7-30 packs per tileset: far below 750 |

How to get bytes: call `estimateTileRegion`, or download one test region and read `completedResourceSize`. Don't assume a KB-per-tile value.

## 3. Topography

| Fact | Tag | Source URL |
|---|---|---|
| Outdoors `mapbox://styles/mapbox/outdoors-v12` is "terrain-focused" and shows **contour lines, hillshading, hiking trails**, national parks, ski runs and land cover. | sourced | https://docs.mapbox.com/map-styles/reference/outdoors/ |
| Outdoors data sources: Mapbox Streets v8, Mapbox Terrain v2 and Mapbox Bathymetry v2. Terrain-DEM is not listed. | sourced | https://docs.mapbox.com/map-styles/reference/outdoors/ |
| **Caveat:** Outdoors is a classic style, "no longer actively maintained". It gets no fixes or features. Mapbox recommends Standard for new projects. | sourced | https://docs.mapbox.com/map-styles/guides/classic-styles/ |
| Trails come from the Streets v8 `road` layer, `class=path`, with `type` values `hiking`, `trail`, `path`, `footway`, `bridleway`, `steps`, `cycleway`, `mountain_bike`, `piste` and others. `type` is mostly the OSM `highway` value, but path types use "custom type assignments" from several OSM tags. The exact tags are not listed, and `sac_scale` is not mentioned. | sourced | https://docs.mapbox.com/data/tilesets/reference/mapbox-streets-v8/ |
| Terrain v2 contours: vector, `ele` in meters. Interval by zoom: z9 500 m, z10 200 m, z11 100 m, z12 50 m, z13 20 m, z14+ 10 m. Full coverage from z12. Also has a `hillshade` polygon layer. | sourced | https://docs.mapbox.com/data/tilesets/reference/mapbox-terrain-v2/ |
| Standard has no terrain, hillshade or contour config property. Its only trail toggle is `showPedestrianRoads`. The classic-styles page does say Standard's 3D environment includes "terrain". **Result: Standard has no documented topo mode.** | sourced (by absence) | https://docs.mapbox.com/map-styles/standard/api/ , https://docs.mapbox.com/map-styles/guides/classic-styles/ |
| Outdoors can be a style pack: `loadStylePack` takes any Mapbox-hosted style URI. A help example uses the classic `light-v11`. | reasoned | https://docs.mapbox.com/help/dive-deeper/mobile-offline/ |
| DEM offline **is supported**: tile regions accept the "Mapbox Raster DEM tile v1 URL schema", and changelog 10.4.0-beta.1 says "Enable tile packs for DEM terrain tiles, it includes both Offline API and TileStoreUsageMode::ReadAndUpdate". | sourced | https://docs.mapbox.com/android/maps/guides/offline/concepts/ , https://github.com/mapbox/mapbox-maps-android/blob/main/CHANGELOG.md |
| To include DEM, either use a style with a `raster-dem` source, so the descriptor picks it up, or add `mapbox.mapbox-terrain-dem-v1` via `TilesetDescriptorOptions.Builder.tilesets`. | reasoned | https://docs.mapbox.com/android/maps/guides/migrate-to-v11/ |
| Terrain v2 contours are included the same way, through the Outdoors style descriptor (its composite source) or `tilesets`. | reasoned | https://docs.mapbox.com/android/maps/guides/offline/concepts/ |
| Terrain-DEM v1: PNG RGB-encoded, data up to **z14**, 0.1 m steps, `height = -10000 + (R*65536 + G*256 + B)*0.1`. "Available only in the Mapbox SDKs". The example uses `tileSize(514)`. | sourced | https://docs.mapbox.com/data/tilesets/reference/mapbox-terrain-dem-v1/ , https://docs.mapbox.com/android/maps/examples/android-view/use-3d-terrain/ |
| No current doc says terrain or raster-dem is not cached offline. | reasoned (absence) | https://docs.mapbox.com/android/maps/guides/offline/concepts/ |

## 4. Elevation lookup on-device

| Fact | Tag | Source URL |
|---|---|---|
| The API is `ElevationData.getElevation(Point): Double?` (that `MapboxMap` exposes it is reasoned). It returns meters × the current terrain exaggeration. There is no `queryElevation` on Android; that name is GL JS `queryTerrainElevation`. | sourced | https://docs.mapbox.com/android/maps/api/11.4.0/mapbox-maps-android/com.mapbox.maps/-elevation-data/get-elevation.html |
| "Value is available only for the visible region on the screen, if terrain (DEM) tile is available." | sourced | https://docs.mapbox.com/android/maps/api/11.4.0/mapbox-maps-android/com.mapbox.maps/-elevation-data/get-elevation.html |
| It works offline if the DEM packs are in the tile store and terrain is enabled in the style. The renderer reads the same DEM tiles. | reasoned | (from the two rows above + concepts DEM support) |
| Real limit: no lookup for arbitrary off-screen points such as a whole GPX track. Options: set exaggeration to 1 or divide by `getTerrainExaggeration()`, or decode DEM PNGs yourself. The SDK has no documented public way to read raw pack tiles. | reasoned | https://docs.mapbox.com/android/maps/api/11.2.1/mapbox-maps-android/com.mapbox.maps/-elevation-data/ |
| Alternatives: Tilequery API on the Terrain v2 `contour` layer (`ele`, 10 m steps, returns stacked polygons), or Raster Tiles API Terrain-RGB PNG decode. | sourced | https://docs.mapbox.com/data/tilesets/guides/access-elevation-data/ |
| Both alternatives are HTTP APIs, so they are online-only. Tilequery: 100,000 free requests/month, then $1.50 per 1,000. | reasoned (online) / sourced (price) | https://www.mapbox.com/pricing |

## 5. Legal (Product Terms, Oct 20 2026)

| Fact | Tag | Source URL |
|---|---|---|
| **The word "offline" does not appear in the Product Terms.** The Terms of Service (updated Mar 31 2024) don't mention it either. | sourced (grep of both) | https://www.mapbox.com/legal/product-terms , https://www.mapbox.com/legal/tos |
| **2.8.1:** "Customer may cache that Licensed Map Content on an End User's device but caching is limited to thirty (30) days on the same device making the Mapping API request... On mobile devices, Customer shall only cache up to the limits set in the Mobile SDKs, and Customer shall not circumvent or change those limits." | sourced | https://www.mapbox.com/legal/product-terms (PDF) |
| Reading: SDK tile regions are the sanctioned mobile cache "up to the limits set in the Mobile SDKs" (the 750 packs). Whether the 30-day cap also applies to tile regions is not stated. Safe path: refresh each region at least every 30 days when online. | reasoned | https://www.mapbox.com/legal/product-terms (PDF) |
| **1.9** default restrictions: "(iii) not scrape or systematically download Licensed Map Content ... (v) not export, download, cache or store", "Except as otherwise expressly permitted". 2.8.1 is that exception. | sourced | https://www.mapbox.com/legal/product-terms (PDF) |
| **1.10** no redistribution. The docs add that offline data "may not be preloaded, bundled or otherwise redistributed". So no shipping region files in the APK and no sharing between phones. Each phone downloads its own. | sourced | https://docs.mapbox.com/android/maps/guides/offline/concepts/ |
| **2.9.1** use the Mobile SDKs as the exclusive means of access in mobile apps. **2.9.2** apps must use an SDK release from the last 12 months. 11.32 (Oct 2026) is fine. | sourced | https://www.mapbox.com/legal/product-terms (PDF) |
| **1.3.4** prohibits "operating a product or service where the use or failure of the Service Offerings could reasonably be expected to lead to death, personal injury". Relevant to no-signal hiking navigation. Mitigation: don't make the app the sole safety tool. | sourced (text) / reasoned (applicability) | https://www.mapbox.com/legal/product-terms (PDF) |
| **1.3.10** forbids extra accounts to stay in the free tier. **3.49** MAU = one install that uses an SDK in a calendar month. | sourced | https://www.mapbox.com/legal/product-terms (PDF) |
| I found no free-tier or pay-as-you-go restriction on offline. Offline in a personal mobile app is permitted within SDK limits. | reasoned | https://www.mapbox.com/legal/product-terms , https://www.mapbox.com/legal/tos |
| Side note: **1.2.2** requires an extra license for vehicle-usage apps. It doesn't affect hiking, but it is relevant to the Nav SDK in a car head unit. | sourced | https://www.mapbox.com/legal/product-terms (PDF) |

## 6. Pricing

| Fact | Tag | Source URL |
|---|---|---|
| Mobile Maps SDK is billed per MAU. **Free up to 25,000 MAU**, then $4.00 per 1,000 (25,001-125,000), $3.20 per 1,000 (to 250,000), $2.40 per 1,000 above that. | sourced | https://www.mapbox.com/pricing |
| "Maps SDK MAUs cover any use of the Vector Tiles API and Raster Tiles API". | sourced | https://docs.mapbox.com/android/maps/guides/pricing/ |
| "Resources downloaded for offline use are included in the regular monthly active user (MAU) billing." | sourced | https://docs.mapbox.com/help/dive-deeper/mobile-offline/ |
| **Conflict, legacy only:** for SDK v8.3+ (v9 docs), offline was "not included in MAU billing", "measured in tile requests". This does not apply to v11. | sourced | https://docs.mapbox.com/android/legacy/maps/guides/pricing/ |
| The pricing page links to `.../android/maps/guides/offline/#pricing-for-offline-maps`, but that section no longer exists on the page. | sourced | https://docs.mapbox.com/android/maps/guides/offline/ |
| The pricing page lists no terrain/DEM line item. Terrain-DEM is "available only in the Mapbox SDKs", so it falls under MAU. | sourced (absence) / reasoned | https://www.mapbox.com/pricing , https://docs.mapbox.com/data/tilesets/reference/mapbox-terrain-dem-v1/ |
| 2 users ≈ 2-4 MAU (a reinstall adds one). That is $0, far under 25k. | reasoned | https://docs.mapbox.com/android/maps/guides/pricing/ |

## Gaps

- Is the 750-pack limit raisable, and is it per tile store or per account? Not documented.
- No bytes-per-tile or MB-per-km² figure is published. Measure with `estimateTileRegion` or `completedResourceSize`.
- How many tilesets (so packs) does one descriptor produce for Standard or Outdoors? Composite sources may count once or per tileset. Not documented.
- Does the outdoors-v12 style JSON declare a `raster-dem` source, so DEM comes automatically? Unconfirmed: needs a fetch of the style JSON with a token.
- The OSM tag to `class=path` `type` mapping (e.g. `sac_scale`, `highway=track`) is not published.
- No numeric expiry period for style or tile packs, and no statement on whether 2.8.1's 30-day cap applies to tile regions.
- No current doc says `getElevation` works offline (reasoned yes). There is no public API for elevation off-screen or along a track.
- The Product Terms PDF is the October 20, 2026 version. The web page itself shows no date.
