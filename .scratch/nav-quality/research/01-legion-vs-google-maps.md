# LEGION navigation against Google Maps: what each has, and what the terms allow

Research for `.scratch/nav-quality/map.md`. Researched 2026-11-06. Facts first; the ranking lives
in the map.

Tags (CLAUDE.md sec 8):

- `sourced`: read on a primary page or in a primary PDF, URL given, fetched 2026-11-06
- `tested`: read from the SDK jars in the Gradle cache with `javap` (Search SDK 2.32.0, Nav SDK 3.32.0)
- `traced`: read in this repo at `feat/nav-audit`
- `reasoned`: inferred from the above, not observed

| Key | Source |
|---|---|
| GMP-SST | Google Maps Platform Service Specific Terms, https://cloud.google.com/maps-platform/terms/maps-service-terms (last modified October 28, 2026) |
| GMP-UIKIT | https://developers.google.com/maps/documentation/places/android-sdk/places-ui-kit-overview |
| MB-PT | Mapbox Product Terms PDF, "Last Updated: October 20, 2026", linked from https://www.mapbox.com/legal/product-terms |
| MB-SBX | https://docs.mapbox.com/api/search/search-box/ |
| MB-PRICE | https://www.mapbox.com/pricing |
| MB-REROUTE | https://docs.mapbox.com/android/navigation/guides/turn-by-turn-navigation/rerouting-and-refresh/ |
| FSQ-OS | https://docs.foursquare.com/data-products/docs/access-fsq-os-places |
| OVERTURE | https://docs.overturemaps.org/attribution/ |

---

## 1. What LEGION does today

| Area | Today | Tag |
|---|---|---|
| Resolver order | saved places, calendar, calendar suggestions, contacts, then Mapbox search; first match decides | traced (`MidnightApplication.navResolver`, `DestinationResolver`) |
| Text search | Search SDK `search()` (suggest), then `select()` on **at most 3** suggestions, **one after another** (`MAX_SELECTS = 3`), even though the query asks for 5 | traced (`MapboxPlaceSearch.textSearch`) |
| Category search | "nearest X" maps ~25 phrases to a Mapbox category id; anything else is a text search | traced (`SearchPhrase.CATEGORIES`) |
| Fuzzy / spoken names | `QueryText.normalize` strips filler and articles; saved places match exact or contains; no typo tolerance, no "X near Y" | traced |
| Ranking | Mapbox's own order; local sources sort by distance; no recency, frequency or open-now | traced |
| Recents / favourites | none (mapbox-plus ticket 03, open) | traced |
| Routing | `applyDefaultNavigationOptions()` + `alternatives(true)`; profile `driving-traffic`, annotations `congestion_numeric`, `maxspeed`, `closure`; `enableRefresh(...)` is called but its argument was not read | tested (`RouteOptionsExtensions` bytecode); refresh value reasoned |
| Live re-ETA | SDK route refresh every 5 min when `enableRefresh` is true; continuous alternatives every 5 min by default | sourced (MB-REROUTE) |
| Faster-route offer | none in LEGION; MB-REROUTE documents no faster-route API | traced; sourced |
| Lanes | not shown; the SDK ships `MapboxLaneIconsApi`, `Lane`, `LaneIndicator` in `tripdata` | traced; tested |
| Incidents | `TrafficSummary` reads congestion and typical duration; no incident is spoken | traced |
| Destination point | the result's `coordinate`; `SearchResult.routablePoints` (the entrance) is ignored | traced; tested (`getRoutablePoints()`) |
| Measurement | `NAV_AUDIT` since this branch; before it, `Log.w` only | built |

## 2. What Mapbox Search can do that LEGION does not use

All `tested` against the Search SDK 2.32.0 jar unless marked.

- `SearchEngine.forward(query, ForwardSearchOptions, callback)`: one request, full results with
  coordinates. MB-SBX: `/forward` "is billed per request". Replaces suggest plus three sequential selects.
- `select(List<SearchSuggestion>, SearchMultipleSelectionCallback)`: resolves several suggestions in one call.
- `brandSearch(...)`: chain names ("the Starbucks").
- `SearchOptions.Builder`: `fuzzyMatch`, `origin`, `navigationOptions` (ETA per result), `types`,
  `unsafeParameters` (passes Search Box query parameters the builder lacks).
- `SearchResult`: `getRoutablePoints()`, `getEtaMinutes()`, `getMetadata()`.
- `SearchResultMetadata`: `getOpenHours()`, `getRating()`, `getReviewCount()`, `getPriceLevel()`,
  `getPopularity()`, `getPhone()`, `getWebsite()`, parking fields.
- `registerDataProvider(IndexableDataProvider)`: the SDK searches the app's own records with
  Mapbox's matcher. It is on the engine LEGION deliberately does not use
  (`createSearchEngineWithBuiltInDataProviders`); a provider LEGION registers itself holds only
  LEGION's own rows (reasoned; not built).
- MB-SBX (sourced): `open_now`, `minimum_rating`, `price_levels` filter on data that exists
  "if available"; `eta_type=navigation` adds drive-time ETA (billed as Matrix elements); supported
  geographies are the United States, Canada and Europe; default rate limit 10 requests per second.

## 3. Prices (sourced, MB-PRICE, monthly)

| Product | Free | Then |
|---|---|---|
| Search Box sessions | up to 2,500 | $11.50 per 1,000 |
| Search Box per request (`/forward`, `/category`) | up to 25,000 | $1.70 per 1,000 |
| Directions | up to 100,000 | $2.00 per 1,000 |
| Matrix (per element) | up to 100,000 | $2.00 per 1,000 |
| Nav SDK v3 | 100 MAU, 1,000 trips | $0.30 per MAU, $0.08 per trip |

The page also lists an "introductory preview" Search Box tier (500 sessions, 50,000 requests free).
Which tier a new token gets was not checked. Two users fit every free tier above (reasoned).

## 4. What the terms allow

### Mapbox (MB-PT, last updated 2026-10-20)

- **2.7.2:** "Customer shall not export, store, or cache Temporary Geocodes." Why `NAV_AUDIT` never
  logs a Mapbox result's name or address.
- **2.7.5:** POI Results may not be used "except in conjunction with a Mapbox Map". LEGION's nav
  screen is a Mapbox map.
- **2.7.6:** "Customer may store and use Mapbox Feature IDs included in Geocoding Results for its
  internal business purposes." This is the honest path for recents and for an auditable pick: keep
  the `mapbox_id`, re-retrieve it when needed (reasoned; Kevin's ruling, map decision D1).
- **2.10.1:** "Customer shall not export, download, cache or store results from any request to a
  Navigation API." `NAV_AUDIT`'s `route_request`/`trip_start` lines keep distance, ETA and rounded
  coordinates, which a strict reading counts as storing results. Built as Kevin asked; flagged as
  decision D2.
- **1.2.2:** a vehicle-usage licence. Already ruled on: mapbox-nav ticket 13.

### Google (GMP-SST, last modified 2026-10-28)

- **14.2:** "Customer must not use Google Maps Content from the Places API in conjunction with a
  non-Google map." Google Places search feeding a Mapbox map and Mapbox routing is out.
- **14.3:** Places lat/lng may be cached "up to 30 consecutive calendar days".
- **15.1:** Places UI Kit "with or without any map, including a non-Google Map". The exception is
  for the kit's own UI.
- **15.3(c):** the UI Kit's use with a third-party service must still respect "the restrictions
  against caching and exporting Google Maps Content". Taking a place out of the kit to route it on
  Mapbox reads as exporting it (reasoned). GMP-UIKIT does not say whether the Android components
  expose coordinates or a place ID to the app, and bills "per component instantiation".
- Any Google path needs a Google Maps Platform key with billing, which is a new household key, and
  reopens ADR 0054 ("never hands a trip to another map app"; "other map SDKs ... not reopened").

### Open data

- **FSQ OS Places:** "Licensed under the Apache License, Version 2.0". Downloaded through the Places
  Portal with an access token (FSQ-OS, sourced). Storable and queryable on the household engine.
- **Overture places:** per source; most "Available under CDLA Permissive 2.0", Foursquare's under
  Apache 2.0 (OVERTURE, sourced).
- Neither carries hours or ratings at Google's depth (reasoned; FSQ-OS does not list the fields).

## 5. Where Google Maps is ahead, honestly

| Area | Google Maps | LEGION | Gap source |
|---|---|---|---|
| POI coverage and freshness | very large, crowd-edited, reviews, photos, popular times | Search Box: US/CA/EU; hours and rating "if available" | data (reasoned) |
| Spoken and fuzzy names | semantic, typo tolerant, "near work" | filler stripping; no fuzzy, no "near Y" | code (traced) |
| Personal context | labelled places, history, reservations from mail | saved places, calendar, suggestions, contacts; no recents | code (traced) |
| Ranking | distance, prominence, open now, personal history | Mapbox order, or distance | code (traced) |
| Traffic and re-ETA | crowd-sourced from Android | Mapbox telemetry; refresh every 5 min | data (reasoned) |
| Faster route mid-trip | offered | not offered | code (traced) |
| Lanes, speed limit | shown | speed limit read; lanes not drawn | code (traced) |
| Incidents | Waze reports, spoken | congestion summary only | code + data |
| Entrance routing | routes to the entrance or parking | routes to the result's point | code (traced) |

**The gaps LEGION can close are the code ones.** POI coverage and crowd traffic are Google's data,
and no term above lets LEGION borrow it onto a Mapbox map.
