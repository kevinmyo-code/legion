# What location data can LEGION actually get, and on what terms?

Type: research
Status: resolved
Blocked by: -

## Question

Kevin wants LEGION to know where he is and answer: severe weather, disasters, traffic (on request),
and area data like crime stats. Most of this is keyless US-government data; two pieces are not.
Surface the facts from each source's OWN documentation:

1. **Severe weather.** NWS `api.weather.gov`: alerts-by-point endpoint, required User-Agent header
   policy, rate limits, alert severity/urgency/certainty fields, and whether polling is sanctioned.
2. **Earthquakes/disasters.** USGS earthquake GeoJSON feeds (radius queries), FEMA OpenFEMA
   declarations API, and the current sanctioned wildfire feed (NASA FIRMS and/or NIFC ArcGIS).
   Keyless? Rate limits? Update cadence?
3. **Air quality.** AirNow API: key signup terms, free tier, by-lat/lon endpoint.
4. **Traffic and ETA.** The hard one - no keyless source exists. Compare on **terms a private
   individual can accept**: Google Routes API (does it REQUIRE a Cloud billing account with a card
   on file, what is the current free monthly credit, does traffic-aware routing cost a premium
   SKU), TomTom, HERE. Report current published pricing and free tiers, and flag ToS clauses on
   caching/storing results.
5. **Crime statistics.** FBI Crime Data Explorer API: key signup, what granularity is actually
   returned (agency-level? city? never neighborhood?), what lag (report year vs current), and
   whether NIBRS coverage is national or partial. **State plainly what a citizen-level question
   like "is this area safe" can and cannot be honestly answered with from this source.**
6. **Local incident feeds.** Whether Kevin's own city/county publishes a police-incident open-data
   feed (Socrata/ArcGIS pattern) and its schema. **Kevin's city is not recorded in this repo - if
   it cannot be determined, say so and describe the general pattern plus how to check.**

Write findings to `research/14-location-intel.md`, cite every claim to the owning URL, mark
anything unverified. Append the Answer here and set Status: resolved.

## Answer

Resolved 2026-08-16. Full findings with per-claim URLs and `doc`/`live`/`unverified` tags:
`research/14-location-intel.md`.

**Four of six areas are fully keyless. No recommended source requires a payment card.**

### 1. Severe weather - NWS

Keyless. `User-Agent` header required instead ("A User Agent is required to identify your
application"), and the docs say it "will be replaced with an API key in the future". Point query
confirmed live 2026-08-16: `GET api.weather.gov/alerts/active?point={lat},{lon}` -> 200, GeoJSON
FeatureCollection. Rate limit deliberately unpublished; retry "typically within 5 seconds".
**Polling is sanctioned with a floor: "no more than every 30 seconds."** severity/urgency/certainty
are CAP fields; NWS does not enumerate them inline - read `api.weather.gov/openapi.json` before
hardcoding an enum (`unverified`).

### 2. Earthquakes / disasters / fire

- **USGS**: keyless. Radius via `latitude`/`longitude`/`maxradiuskm`, 20,000-result hard cap. USGS
  explicitly steers automated clients to the **real-time GeoJSON summary feeds, "Updated every
  minute"** - use those, not `fdsnws/event/1/query`, on any timer.
- **FEMA OpenFEMA**: keyless, confirmed live. County-level (`fipsStateCode`/`fipsCountyCode`/
  `designatedArea`). It is a *declaration*, not a hazard - context, never an alert. Published rate
  limit / max `$top` `unverified`: both FEMA doc pages returned 403 to the research tool.
- **NASA FIRMS**: free MAP_KEY. **5000 transactions / 10-minute interval.** NRT within 60 min of
  overpass, URT under 60 s for much of US/Canada. Caveat: a FIRMS pixel is a thermal anomaly, not a
  fire. Speak it as "satellite heat detection".
- **NIFC WFIGS**: keyless ArcGIS FeatureServer, confirmed live (`Query,Extract`, maxRecordCount
  2000, `supportsQueryWithDistance: true`). Gives `IncidentName`, `IncidentSize`,
  `PercentContained`. **Better wildfire source for a spoken assistant than FIRMS.**

### 3. Air quality - AirNow

Free self-service key; keyless call returns 401 (confirmed live). Data updates hourly. Caching
encouraged, bulk polling discouraged. Rate limits are per-service and behind the login wall
(`unverified`). Mandatory disclaimer: data "are not fully verified or validated and should be
considered preliminary and subject to change."

**Flag:** the web-services index carries a heading, verbatim, **"Web Services that will be retired
in the fall of 2026"**, and lat/lon variants appear under it. A differently-named lat/lon service
appears on the surviving list. Ambiguous from the index alone, and fall 2026 is weeks away. Log in
and read the specific service page before writing the client.

### 4. Traffic - vendor comparison

| | Google Routes | TomTom | HERE |
|---|---|---|---|
| Cloud billing account | **Required** | No | **Required** (Base plan) |
| **Card on file** | **Yes, in practice** | **No** - "no upfront credit card needed" | **Yes** - "requires entering a payment method" |
| Traffic-aware = premium SKU? | **Yes** (Pro tier) | **No** - default in Routing | `unverified` |
| Free traffic-aware calls/mo | 5,000 (Compute Routes Pro) | 20,000 (Routing API) | `unverified` |
| Price past free | $10.00 / 1,000 | not publicly printed | `unverified` |
| $200 monthly credit | **Gone.** Only a $300 / 90-day trial | n/a | n/a |
| Caching posture | Most restrictive; 30-day lat/lon carve-out | `unverified` | `unverified` |

Google, verbatim: "To use the Routes API, you must enable billing on each of your projects";
traffic-aware billing applies to "requests that use an advanced feature, such as the
`TRAFFIC_AWARE` or `TRAFFIC_AWARE_OPTIMAL` route modifiers", which is the **Pro** tier. The exact
feature Kevin wants is the premium SKU by construction.

HERE, verbatim: "Effective March 27, the HERE Limited Plan has been officially decommissioned for
new customers... The Base plan requires entering a payment method during the onboarding process."
The no-card Limited plan is closed to Kevin.

**Verdict: TomTom.** Only vendor with no card. Traffic-aware ETA is the *default* of the free 20K
Routing API, not a premium tier - 4x Google's traffic-aware headroom. Failure mode without a card
is a hard stop, not a surprise bill. Google is the better product; that is not the question asked.
Guardrails: key in Keystore via `KeyVault`, on-request only (never polled), do not persist ETAs
until TomTom's caching clause is actually read, offline stated in words, "per TomTom" when spoken.

### 5. Crime - FBI CDE

Free api.data.gov key; `DEMO_KEY` works. Confirmed live 2026-08-16:

- **Granularity is the reporting agency's jurisdiction.** Records key on `ori`, `agency_name`,
  `agency_type_name`, `counties`. The lat/lon present is the **agency's own location**, not an
  offense location. No neighborhood, no block, no tract; not even reliable city, since overlapping
  city/county/state/campus forces all report separately over the same ground.
- **Lag is roughly 13 months.** A summarized agency query on 2026-08-16 returned complete data only
  through **07-2025**. Annual releases lag similarly (2024 stats released 2025).
- **Coverage is national but voluntary and incomplete.** 2024: >16,000 agencies, 95.6% of
  population; NIBRS submitters were 87.2% of the population covered by 19,328 enrolled agencies;
  2,074 agencies still on SRS.

**Honesty verdict: "is this area safe" cannot be honestly answered from this source. Not
partially, not with a caveat.** Wrong geography (jurisdiction, not neighborhood), wrong recency
(~13 months), wrong denominator (measures reporting propensity as much as crime), incomplete
voluntary participation, and the safety inference simply is not in the data.

What it *can* support: "Agency X reported N violent-crime offenses in 2024, per the FBI's Crime
Data Explorer, the most recent complete year" - a historical count bound to a named agency and
year; multi-year trends in *reported offenses*; agency-vs-state-vs-national comparison.

**Design rule:** do not ship `is_area_safe`. Ship `get_reported_crime_history(...)` whose tool
description states in words: agency-level only, ~1 year stale, voluntary reporting, does not answer
whether a place is safe. On "is it safe here", say plainly it cannot be answered from this data,
then offer the historical figure. The refusal is the feature. This is §4 rule 5 in its strongest
form - not even "estimate" is an honest label here, because there is no estimate to make.

### 6. Local incident feeds - city not determinable

**Kevin's city is NOT recorded in this repo.** Checked 2026-08-16: grep of `.scratch/` for
city/address/ZIP/county terms found nothing; a 35-city-name grep matched only ledger/pantry test
fixtures and archived agent files; a grep of `app/src/main` for `homeLat`/`defaultLat`/
`DEFAULT_LOCATION`/`ZoneId.of`/`America/*` hit only **doc comments** (`America/Chicago` twice,
`America/Los_Angeles` once) used as worked examples in bug narratives. That weakly suggests US
Central, is an inference from a comment, is not a city, and **must not be treated as a
determination.** Resolve by asking Kevin, or better, reverse-geocode at runtime via the existing
`Geocoder` / `LocationController` path - hardcoding a hometown breaks the moment he travels.

General pattern, city-independent:
- **Socrata**: `https://{portal}/resource/{id}.json` with `$where`/`$select`/`$order`/`$limit`.
  Typical columns: incident number, offense, reported datetime, **block-level** address, lat/lon,
  district/beat, disposition. Note dev.socrata.com now documents **SODA v3.0 (2025)** at
  `/api/v3/views/{id}/query.json` and states queries "must be either authenticated by a user or
  marked with a valid application token" - i.e. app token required under v3. Whether a given city
  has migrated, and whether its legacy `/resource/` still answers anonymously, is `unverified`.
- **ArcGIS Hub**: `.../FeatureServer/0/query` with `where=1=1&outFields=*&f=geojson`, plus
  `geometry`/`distance`/`spatialRel`. Usually keyless. **Fetch the layer root with `?f=pjson`
  first** - one request reveals `Query` support, `maxRecordCount`, and distance-query support
  before any client code is written.
- Find one: search `{city} open data portal`, then "police incident" / "calls for service", then
  hit the real endpoint and read the real schema. Most are daily-batch, not near-real-time.
- Same honesty caveat scaled down: a block-level feed is fresher and finer than FBI CDE, and it
  still reports *reports*, not safety.

### Open items (all `unverified`, priority order)

1. Which AirNow lat/lon endpoint survives fall-2026 retirement. Weeks away.
2. TomTom's actual caching clause, before any ETA is persisted to Room or Drive.
3. NWS severity/urgency/certainty enums from `api.weather.gov/openapi.json`.
4. OpenFEMA rate limit and max `$top` (doc pages 403'd).
5. Kevin's city - ask, or resolve at runtime.
