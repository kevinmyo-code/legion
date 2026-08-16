# Location intelligence sources: what LEGION can get, and on what terms

Research for ticket `14-location-intel-research.md`. Date: 2026-08-16.
Every claim below is tagged with how it was established:

- `doc` - read from the owning vendor's own documentation page (URL given).
- `live` - confirmed by issuing the actual request during this research on 2026-08-16.
- `unverified` - could not be confirmed against a primary source; stated as open.

No claim here is from a blog, aggregator, or Stack Overflow unless explicitly marked.

---

## 0. Headline

| Source | Key? | Card? | Verdict for LEGION |
|---|---|---|---|
| NWS `api.weather.gov` | No | No | Use. Point query works today |
| USGS earthquakes | No | No | Use. Feeds update every minute |
| FEMA OpenFEMA | No | No | Use. Coarse (county-level declarations) |
| NASA FIRMS | Yes (free) | No | Use for fire detections |
| NIFC WFIGS ArcGIS | No | No | Use for named-incident perimeters |
| AirNow | Yes (free) | No | Use, but the lat/lon endpoint is on a retirement list |
| Google Routes | Yes | **Yes** | Reject on terms, not on quality |
| TomTom | Yes (free) | **No** | **Recommended traffic vendor** |
| HERE | Yes | **Yes** | Reject, same reason as Google |
| FBI CDE | Yes (free) | No | Use only for honest historical framing, never for "is it safe" |
| City/county incident feeds | Usually no | No | Cannot be specified: Kevin's city is not in this repo |

---

## 1. Severe weather - NWS `api.weather.gov`

### Access

- **No API key.** A `User-Agent` header is required instead. `doc`
  > "A User Agent is required to identify your application. This string can be anything, and the
  > more unique to your application the less likely it will be affected by a security event."
  Suggested form: `User-Agent: (myweatherapp.com, contact@myweatherapp.com)`.
  Source: https://www.weather.gov/documentation/services-web-api
- The same page states the User-Agent "will be replaced with an API key in the future." `doc`
  Treat keyless access as **not permanent**. Design the client so a key can be added without a
  rewrite.

### Point query

- `GET https://api.weather.gov/alerts/active?point={lat},{lon}` works. `live`
  Issued for `38.8894,-77.0352` on 2026-08-16: HTTP 200, GeoJSON `FeatureCollection`,
  `"title": "Current watches, warnings, and advisories for 38.8894 N, 77.0352 W"`,
  `"updated": "2026-08-16T06:22:14+00:00"`, `@context` version 1.1, `features: []` (nothing
  active there at that moment).
- `/alerts/active` is documented as redirecting internally to `/alerts?active=true`. `doc`
- Other documented filters: `area` (state, e.g. `?area=KS`), `zone` (e.g. `ALZ034`), `region`
  (e.g. `AT`). The alerts page's parameter header also lists `urgency, severity, certainty,
  status, message_type, event, limit, cursor`. `doc`
  Source: https://www.weather.gov/documentation/services-web-alerts

### Rate limits and polling

- Rate limit value is **deliberately not published**. `doc`
  > "there are reasonable rate limits in place to prevent abuse and help ensure that everyone has
  > access. The rate limit is not public information, but allows a generous amount for typical use."
  On exceeding it, requests error and "may be retried after the limit clears (typically within
  5 seconds)."
- **Polling is explicitly sanctioned, with a floor.** `doc`
  > "We recommend you make requests of the server no more than every 30 seconds."
  Source: https://www.weather.gov/documentation/services-web-alerts
- The API "uses a cache-friendly approach that expires content based upon the information life
  cycle." `doc` Honour `Cache-Control` / `Expires` rather than inventing a TTL.

### Severity / urgency / certainty

- These are **CAP (Common Alerting Protocol) fields**, not NWS inventions. The NWS API docs do not
  enumerate the allowed values inline; they defer to CAP documentation. `doc`
- Values as understood from CAP and NWS material: `unverified` against a single primary page.
  - `severity`: Extreme, Severe, Moderate, Minor, Unknown
  - `urgency`: Immediate, Expected, Future, Past, Unknown
  - `certainty`: Observed, Likely, Possible, Unlikely, Unknown
  Confirm against `https://api.weather.gov/openapi.json` before hardcoding an enum. That file is
  the machine-readable spec and is the correct source of truth; it was not parsed in full here.

### LEGION implications

- Keyless, so it fits BYO-key-free operation. No Keystore entry needed today.
- Poll no faster than 30s. A foreground "watch" that polls every 30s is inside the sanctioned rate;
  anything faster is not.
- **Offline degradation:** the last-fetched alert set is stale weather, and stale severe-weather
  data is actively dangerous. Any spoken answer from cache must say the fetch time in words.
- Attribution when spoken: "the National Weather Service."

---

## 2. Earthquakes, disasters, wildfire

### 2a. USGS earthquakes

- **No API key.** Documentation contains no mention of one. `doc` `live`
  Source: https://earthquake.usgs.gov/fdsnws/event/1/
- Radius query parameters: `latitude`, `longitude`, `maxradiuskm` (0 to 20001.6),
  or `maxradius` in degrees (mutually exclusive with `maxradiuskm`). `doc`
- Formats: `geojson`, `csv`, `kml`, `xml`, `text`, `quakeml` (default). `doc`
- Hard cap: "The service limits queries to 20000, and any that exceed this limit will generate a
  HTTP response code '400 Bad Request'." `doc`
- **USGS steers automated clients away from the query API**: `doc`
  > "automated applications should use Real-time GeoJSON Feeds for displaying earthquake
  > information whenever possible, as they will have the best performance and availability for
  > that type of information."
- Real-time GeoJSON feeds are "Updated every minute". `doc`
  Source: https://earthquake.usgs.gov/earthquakes/feed/v1.0/geojson.php
  Shape: `/earthquakes/feed/v1.0/summary/{threshold}_{period}.geojson` where threshold is one of
  significant / 4.5 / 2.5 / 1.0 / all and period is hour / day / week / month.
- No published rate limit or attribution requirement found. `doc` (absence, not a permission)

**LEGION pattern:** pull `2.5_day.geojson` or `significant_day.geojson`, filter by distance
client-side. Do NOT hit `fdsnws/event/1/query` on a timer - USGS says not to. Reserve the query
API for a one-off "any quakes near me in the last month" voice request.

### 2b. FEMA OpenFEMA

- **No API key.** `live` A request to
  `https://www.fema.gov/api/open/v2/DisasterDeclarationsSummaries?$top=1&$filter=state eq 'VA'`
  returned data with no credential on 2026-08-16. Metadata echoed:
  `"skip":0,"select":null,"top":1,"format":"json","filter":"state eq 'VA'","orderby":"",
  "entityname":"DisasterDeclarationsSummaries","version":"v2"`.
- Fields available on `DisasterDeclarationsSummaries`: `live`
  `femaDeclarationString, disasterNumber, state, declarationType, declarationDate, fyDeclared,
  incidentType, declarationTitle, ihProgramDeclared, iaProgramDeclared, paProgramDeclared,
  hmProgramDeclared, incidentBeginDate, incidentEndDate, disasterCloseoutDate, tribalRequest,
  fipsStateCode, fipsCountyCode, placeCode, designatedArea, declarationRequestNumber,
  lastIAFilingDate, incidentId, region, designatedIncidentTypes, lastRefresh, hash, id`.
- Query syntax is OData-flavoured: `$top`, `$skip`, `$filter`, `$select`, `$orderby`,
  `$inlinecount`. `live` (all echoed in the metadata block)
- **Rate limits and the maximum `$top`: `unverified`.** `https://www.fema.gov/about/openfema/api`
  and `https://www.fema.gov/about/reports-and-data/openfema` both returned **HTTP 403** to this
  research tool, so the published limits could not be read. A `$top=10001` request was accepted
  without an error, but the response's `count` was 0 under `metadata=true` semantics, so that is
  not proof the cap is above 10,000. Read the 403'd page from a normal browser before relying on
  any specific cap.

**Granularity note that matters:** the useful geographic key is `fipsStateCode` +
`fipsCountyCode` + `designatedArea`. FEMA declarations are **county-level**, and they describe a
federal declaration, not an active hazard. A declaration can post days after an event and can stay
open for months. This is context ("your county is under a federal disaster declaration from
2026-07-02 for Severe Storms"), not a warning. It must never be spoken as if it were an alert.

### 2c. Wildfire - NASA FIRMS

- **MAP_KEY required, free.** `doc` Obtained via the "Get MAP_KEY" link on the FIRMS API page.
  Source: https://firms.modaps.eosdis.nasa.gov/api/area/
- Rate limit: **"5000 transactions / 10-minute interval."** Multi-day queries consume more than
  one transaction. `doc`
- URL shape: `doc`
  - `/api/area/csv/[MAP_KEY]/[SOURCE]/[AREA_COORDINATES]/[DAY_RANGE]`
  - `/api/area/csv/[MAP_KEY]/[SOURCE]/[AREA_COORDINATES]/[DAY_RANGE]/[DATE]`
- `SOURCE`: `VIIRS_SNPP_NRT`, `VIIRS_SNPP_SP`, `VIIRS_NOAA20_NRT`, `VIIRS_NOAA20_SP`,
  `VIIRS_NOAA21_NRT`, `MODIS_NRT`, `MODIS_SP`, `LANDSAT_NRT` (US/Canada only). `doc`
- `AREA_COORDINATES`: bounding box `west,south,east,north`, or `world`. `DAY_RANGE`: 1-5.
  `DATE`: optional `YYYY-MM-DD`. `doc`
- Latency: NRT "within 60 minutes of satellite overpass"; URT (ultra real-time) "in less than
  60 seconds of satellite fly over for much of the US and Canada." `doc`
- Attribution/usage terms: not stated on the API page. `unverified`

**What FIRMS actually is:** thermal anomaly detections from satellites. A detection is not a fire
and a fire is not an emergency. Speaking a FIRMS pixel as "there is a wildfire near you" is a
false-alarm generator (industrial heat, agricultural burns, flares all trigger it). Use it as a
corroborating layer, and say "satellite heat detection", never "wildfire", unless NIFC corroborates.

### 2d. Wildfire - NIFC WFIGS (the named-incident source)

- **Keyless ArcGIS FeatureServer.** `live` Fetched
  `https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/WFIGS_Incident_Locations_Current/FeatureServer/0?f=pjson`
  with no credential on 2026-08-16.
  - Capabilities: `Query,Extract`
  - `maxRecordCount`: 2000 (`standardMaxRecordCount`: 16000)
  - `supportsQueryWithDistance: true`, plus intersects / contains / crosses / within
  - Useful fields: `IncidentName`, `IncidentSize`, `FireDiscoveryDateTime`, `PercentContained`,
    `FinalAcres`, `IncidentTypeCategory`, `EstimatedCostToDate`, `POOState`, `GACC`,
    `ModifiedOnDateTime_dt`
- Portal and dataset landing pages: https://data-nifc.opendata.arcgis.com/ and
  https://wfigs-nifc.hub.arcgis.com/ `doc`
- Update cadence and rate limits: not published on the service metadata. `unverified`

**This is the better wildfire source for a spoken assistant.** It has a human incident name, an
acreage, and a containment percentage - three things a person can act on. FIRMS gives a lat/lon
and a brightness temperature, which is not an answer to "should I be worried".

---

## 3. Air quality - AirNow

- **API key required. Free, self-service signup** at https://docs.airnowapi.org/login. `doc`
  "Access to the AirNow API is available to the public." `doc`
  Source: https://docs.airnowapi.org/docs/
- Key requirement confirmed empirically: a request to
  `https://www.airnowapi.org/aq/observation/latLong/current/?format=application/json&latitude=38.89&longitude=-77.03&distance=25&API_KEY=TEST`
  returned **HTTP 401 Unauthorized** on 2026-08-16. `live`
  The endpoint path and parameter names are therefore correct as written.
- Rate limits: **per-service, documented per web service, not centrally published.** `doc`
  > "Users must limit web service calls for a given API key to the maximum permitted for the web
  > service (see documentation for each web service)."
  And critically:
  > "rate limits cannot be changed, so if you're running into rate limiting issues, you may need
  > to change your query strategy or use a different data source."
  Source: https://docs.airnowapi.org/faq
  The specific hourly number for the lat/lon service is behind the login wall. `unverified`
- Update cadence: "In general, air quality observations are updated once per hour and forecasts
  are issued once per day." `doc`
- **Caching is encouraged, bulk polling is not:** `doc` the FAQ recommends caching data and
  advises against using web services to populate databases through bulk requests, pointing bulk
  users at file products (`reportingarea.dat`) or `dmc@airnowtech.org`.
- Data quality disclaimer that must reach the user: `doc`
  > "These data are not fully verified or validated and should be considered preliminary and
  > subject to change."
  and the data "should not be used to formulate or support regulation, trends, guidance, or any
  other government or public decision making."

### The AirNow finding that changes the design

The web services index carries a section headed **verbatim**:

> "Web Services that will be retired in the fall of 2026"

`doc` Source: https://docs.airnowapi.org/webservices

Services listed under that heading include, verbatim, "By latitude/longitude" under both
**Forecasts** and **Current Observations by Reporting Area**, and "By Zip Code" for the same. `doc`

Services **not** under that heading include "By Zip Code or Lat/Long" under **Current
Observations** and "By Reporting Area, Lat/Long, or Zip Code" under **Current Forecasts**. `doc`

**Reading:** the page's naming is ambiguous enough that it cannot be resolved from the index alone.
A lat/lon capability appears on both the retiring list and the surviving list under differently
named parent services. Fall 2026 is roughly one to three months away from today (2026-08-16), so
this is not a distant concern.

**Action:** log in to https://docs.airnowapi.org/ and read the specific service page for the
endpoint LEGION intends to call before writing the client. Build the AQI call behind one interface
with the endpoint URL in one place. `unverified` which exact URL survives.

---

## 4. Traffic and ETA - the hard one

There is no keyless traffic source. All three vendors require an account and a key. The real
question the ticket asks is which of them a private individual can accept, and the differentiator
is **whether a payment card must be on file**.

### 4a. Google Routes API

**Billing account is mandatory, stated in Google's own words:** `doc`

> "To use the Routes API, you must enable billing on each of your projects and include an API key
> or OAuth token with all API or SDK requests."

Source: https://developers.google.com/maps/documentation/routes/usage-and-billing

**Traffic-aware routing is a premium SKU.** Same page, verbatim: `doc`

> "Billed for requests that use an advanced feature, such as the `TRAFFIC_AWARE` or
> `TRAFFIC_AWARE_OPTIMAL` route modifiers"

and that billing falls under the **Pro** tier, not Essentials. So the exact thing Kevin wants -
"how long will it take me right now, with traffic" - is the expensive SKU by construction. A
non-traffic route is Essentials; the moment you ask for live traffic, you move a tier.

**Current pricing (SKU-level, from Google's pricing table):** `doc`

| SKU | ID | Free/month | 0-100k band |
|---|---|---|---|
| Routes: Compute Routes Essentials | 9EFF-679A-9B16 | 10,000 | $5.00 / 1,000 |
| Routes: Compute Routes Pro (traffic-aware) | 02F7-1B55-DC90 | **5,000** | **$10.00 / 1,000** |

Verbatim row for the Pro SKU: "Routes: Compute Routes Pro / 02F7-1B55-DC90 / 5,000 / $10.00 /
$8.00 / $6.00 / $3.00 / $0.75".
Source: https://developers.google.com/maps/billing-and-pricing/pricing

Free-usage-cap structure by tier: Essentials SKUs typically 10,000/month, Pro typically
5,000/month, Enterprise typically 1,000/month; some SKUs are Unlimited. `doc`

**The $200 monthly recurring credit is gone.** Google's get-started page describes only a trial: `doc`

> "Google Cloud offers a $0.00 charge trial. The trial expires at either end of 90 days or after
> the account has accrued $300 worth of charges, whichever comes first."

and

> "If you've never been a paying customer of Google Cloud, Google Maps Platform, or Firebase, and
> you've never signed up for the Google Cloud Free Trial before, you're eligible for the Free Trial
> and $300 Welcome credit."

The page makes no mention of a $200 recurring monthly credit; it instead says "Each Google Maps
Platform SKU provides a specific amount of free monthly usage."
Source: https://developers.google.com/maps/get-started

**Is a card strictly required?** Google's Routes docs say billing must be enabled. `doc` The Cloud
Billing docs say a Cloud Billing account "operates in a single currency and is linked to a Google
payments profile" and describe a card authorization hold. `doc`
(https://docs.cloud.google.com/billing/docs/how-to/create-billing-account) The docs do not print a
single sentence reading "a credit card is required", so the strict phrasing is `unverified`; the
practical answer - **you cannot enable billing without a payments profile carrying a payment
instrument** - is what the two pages together support.

**Caching / storage restrictions.** Routes policies confirm place IDs are exempt: `doc`

> "the place ID, used to uniquely identify a place, is exempt from the caching restrictions. You
> can therefore store place ID values indefinitely."

Source: https://developers.google.com/maps/documentation/routes/policies

Everything else defers to the Maps Platform Service Specific Terms. §3.2.3 (a) and (b) prohibit
pre-fetching, indexing, storing, or caching Content except under limited stated conditions; the
notable carve-out is that latitude/longitude values may be temporarily cached for **up to 30
consecutive calendar days**, after which they must be deleted. `unverified` verbatim - the terms
page truncated when fetched, so these clauses are reported at the level established by Google's own
policy pages that quote them rather than from §3.2.3 itself. Read
https://cloud.google.com/maps-platform/terms/maps-service-terms directly before relying on the
exact wording.

**Practical consequence for LEGION:** a stored ETA history, a "how bad is my commute usually" trend
table, or a Drive-synced route cache is squarely in the restricted zone. Google's terms are the
most restrictive of the three on retention.

### 4b. TomTom

- **No credit card to start.** Verbatim from TomTom's pricing page: `doc`
  > "No hidden costs, no upfront credit card needed - just start building."
  Source: https://docs.tomtom.com/pricing
- Free monthly allowances, verbatim from that page: `doc`
  - Routing API: "Free 20K monthly"
  - Traffic Flow API Segment Data: "Free 20K monthly"
  - Traffic Incidents API Details: "Free 2.5K monthly"
  - Traffic Flow & Incidents vector/raster tiles: "Free 200K monthly"
  - Map Display API vector tiles: "Free 200K monthly"
  - Places Search API Suggest: "Free 10K monthly"
- Plan names: "Start building", "Pay as you grow", "Enterprise". Paid per-request prices are not
  printed on the public page (only volume bands). `doc`
- **Traffic is on by default in Routing.** The calculate-route docs note: `doc`
  > "Note that even when `traffic=false`, `travelTimeInSeconds` still includes the delay due
  > traffic."
  and expose `computeTravelTimeFor` returning `noTrafficTravelTimeInSeconds`,
  `historicTrafficTravelTimeInSeconds`, `liveTrafficIncidentsTravelTimeInSeconds`.
  Source: https://docs.tomtom.com/routing-api/documentation/tomtom-maps/calculate-route
  **So traffic-aware ETA is not a separate premium SKU at TomTom.** It is the default behaviour of
  the same 20K-free Routing API. This is the single biggest difference from Google.
- **Caching terms: `unverified`.** The legal index at
  https://docs.tomtom.com/legal/terms-and-conditions lists the documents but the fetched page
  contained navigation only, not clause text. A community forum post attributes to Clause 11.4 a
  prohibition on caching "for the purpose of scaling results to serve multiple clients or users" -
  that is a **forum**, not a primary source, and is recorded here only as a thing to go read.
  For LEGION's single-user, two-phone shape, "scaling to serve multiple users" is plausibly not
  engaged, but that must be read from the actual clause before it is relied on.

### 4c. HERE

- **Payment method now mandatory.** HERE's own release notes, verbatim: `doc`
  > "Effective March 27, the HERE Limited Plan has been officially decommissioned for new
  > customers."
  > "New customers must now sign up for the Base plan if they want to test HERE services and SDKs."
  > "The Base plan requires entering a payment method during the onboarding process to access our
  > platform."
  > "Once the payment method is provided, customers will automatically gain access to the Base plan
  > and the included free monthly transactions."
  Source: https://www.here.com/learn/blog/april-2025-platform-release-notes
- The old Limited Plan (the no-card one) allowed "a limit of 1,000 daily Requests" plus per-second
  RPS caps. `doc` (https://www.here.com/get-started/pricing/limited-plan-restrictions) It is closed
  to new signups per the release notes above, so it is **not available to Kevin**.
- **Base plan free monthly transaction count: `unverified`.** https://www.here.com/get-started/pricing
  and https://www.here.com/developer are client-rendered and returned no numeric content to this
  tool. The signup flow (`platform.here.com/sign-up?step=verify-identity`) additionally implies an
  identity-verification step. Read the number from a browser if HERE is reconsidered.
- Base plan excluded use cases: Asset Management, Usage Based Insurance (UBI)/Telematics,
  Optimization. `doc` (restrictions page)
- Caching terms: not read. `unverified`

### 4d. Comparison and recommendation

| | Google Routes | TomTom | HERE |
|---|---|---|---|
| API key | Yes | Yes | Yes |
| **Cloud billing account** | **Required** (`doc`) | Not required | **Required** (Base plan) |
| **Card on file** | Yes, in practice | **"No upfront credit card needed"** (`doc`) | **"requires entering a payment method"** (`doc`) |
| No-card free tier exists? | No | **Yes** | No (Limited plan decommissioned for new customers) |
| Traffic-aware = premium SKU? | **Yes**, Pro tier | **No**, default in Routing | `unverified` |
| Free traffic-aware routes/month | 5,000 (Pro SKU) | 20,000 (Routing API) | `unverified` |
| Price past free | $10.00 / 1,000 | not publicly printed | `unverified` |
| Caching posture | Most restrictive; 30-day lat/lon carve-out | `unverified` | `unverified` |
| Bill-shock risk on a personal card | Real | **None without a card** | Real |

**Recommendation: TomTom.**

Reasoning, in the order that matters for this project:

1. **No card is the whole argument.** LEGION is a sideloaded personal app with no backend and no
   revenue. Attaching a personal credit card to a voice assistant that issues network requests on
   its own initiative is a bill-shock surface with no upside. TomTom is the only one of the three
   that removes it entirely.
2. **Traffic-aware ETA is the free default, not the premium tier.** 20,000 routing calls a month
   against Google's 5,000 traffic-aware calls is 4x the headroom on the exact feature requested.
3. **Failure mode is a hard stop, not a bill.** Without a card, exhausting a free tier degrades to
   an error, which LEGION already has to handle for offline. With a card, exhausting a free tier
   silently bills.
4. Google is the better product and the better data. That is not in dispute and is not the
   question the ticket asked.

**Guardrails if TomTom is adopted:**
- Key goes in the Android Keystore via the existing `KeyVault` BYO path, same shape as the Gemini
  key. No key ships in the repo.
- Traffic is an **on-request** capability, never a poll. A background ETA poller would burn the
  20K allowance and change the app's character.
- Read TomTom's actual caching clause before persisting any route or ETA to Room or to Drive. Until
  it is read, treat ETAs as in-memory and ephemeral.
- Offline: say it in words. "I cannot reach TomTom, so I have no live travel time."
- Attribution when spoken: "per TomTom."

---

## 5. Crime statistics - FBI Crime Data Explorer

### Access

- **API key from api.data.gov**, free self-service signup at https://api.data.gov/signup/. `doc`
- Base host is `https://api.usa.gov/crime/fbi/...`. Two path families exist: the older
  `/crime/fbi/sapi/...` and the current `/crime/fbi/cde/...`. `doc`
- `DEMO_KEY` works for exploration. `live` Confirmed 2026-08-16 against
  `https://api.usa.gov/crime/fbi/cde/agency/byStateAbbr/VA?API_KEY=DEMO_KEY`.

### What granularity actually comes back

`live` The agency listing returned records keyed by **county**, containing **agency** records:

```
ori: "VA0520200"
counties: "LEE"
is_nibrs: true
latitude: 36.701721
longitude: -83.130112
state_abbr: "VA"
state_name: "Virginia"
agency_name: "Pennington Gap Police Department"
agency_type_name: "City"
nibrs_start_date: "1997-12-01"
```

So the geographic unit is **the reporting agency's jurisdiction** - a whole police department, a
whole sheriff's office, a whole university force. There is a lat/lon, but it is the **agency's
own location**, not the location of any offense.

**There is no neighborhood granularity. There is no block, tract, or address granularity. There is
not even reliable city granularity** - a "City" agency type approximates a city, but a county
sheriff's ORI covers unincorporated area of wildly varying character, and overlapping jurisdictions
(city PD, county sheriff, state police, campus police) all report separately over the same ground.

### What lag actually applies

`live` A summarized agency query on 2026-08-16:
`https://api.usa.gov/crime/fbi/cde/summarized/agency/VA0520200/violent-crime?from=01-2019&to=12-2026&type=counts&API_KEY=DEMO_KEY`
returned monthly series for the agency, its state, and the nation. **The most recent period with
complete non-null data across all categories was 07-2025** - roughly **13 months behind the query
date**. Later months were null or partial.

Annual publication lag is similar in shape: the FBI's 2024 statistics were released in 2025. `doc`
(https://www.fbi.gov/news/press-releases/fbi-releases-2024-reported-crimes-in-the-nation-statistics)

### Coverage - national but not complete, and voluntary

From the FBI's own 2024 release: `doc`

- More than 16,000 agencies submitted, "covering a combined population of 95.6%" of the US.
- "The 14,601 agencies that submitted their data via NIBRS represented 87.2% of the population
  covered by the 19,328 agencies actively enrolled in the FBI's UCR Program."
- 2,074 agencies covering 28,572,514 people still submitted via the older SRS, not NIBRS.

So: NIBRS is **majority but not universal**, participation is **voluntary**, and the population
covered is not the same as the agencies enrolled. An agency's absence from the data is not an
absence of crime.

### The honesty verdict

**"Is this area safe?" cannot be honestly answered from the FBI CDE. Not partially, not with a
caveat. The question and the dataset do not meet.**

Five independent reasons, each sufficient on its own:

1. **Wrong geography.** The finest unit is an agency jurisdiction. "This area" to a person means a
   street, a block, a neighborhood. The data has no such unit and never will.
2. **Wrong recency.** The freshest complete month observed was 13 months old. "Is it safe right
   now" is answered with data from last summer.
3. **Wrong denominator.** Counts and even rates are hostage to reporting propensity, agency
   staffing, and classification practice. A department that improves its reporting looks like a
   department whose crime rose.
4. **Voluntary, incomplete participation.** 87.2% of the covered population via NIBRS is a lot and
   is still not everyone. Gaps are non-random.
5. **The inference is not in the data.** "Safe" is a judgement about personal risk. Aggregate
   offense counts over a jurisdiction and a year do not support an individual-level risk claim.
   Making that leap is exactly the class of unanchored assertion §4 rule 5 and §7 forbid.

**What the source CAN honestly support**, if LEGION speaks it precisely:

- "The Pennington Gap Police Department reported N violent-crime offenses in 2024, per the FBI's
  Crime Data Explorer. That is the most recent complete year." - a **historical count attributed to
  a named agency and a named year**.
- A trend across years for one agency, stated as a trend in *reported offenses*, not in *crime*.
- A comparison of one agency's rate to its state and the nation, which the summarized endpoint
  returns alongside. Same caveats, spoken.

**Design rule for LEGION.** The crime tool must not be named or described in a way that invites the
safety question. Do not ship `is_area_safe`. Ship something like
`get_reported_crime_history(agency_or_area)` whose tool description states, in words: agency-level
only, most recent complete data is roughly a year old, voluntary reporting, and that it does not
answer whether a place is safe. When the user asks "is it safe here", the correct behaviour is to
say plainly that this cannot be answered from the data available, then offer the historical figure
if they still want it. That refusal is the feature.

This is the §4 rule 5 pattern exactly: the source does not state safety, so safety cannot be
gated, so it must never be surfaced as fact. It is stronger than the estimate case, because here
even labelling it an estimate would be misleading - there is no estimate to make.

---

## 6. Local incident feeds - and why this one cannot be answered

### Kevin's city is not recorded in this repo

Checked on 2026-08-16:

- Grep across `.scratch/` for city / address / ZIP / county / neighborhood terms: **no matches.**
- Grep across the repo for 35 common US city names: 25 files matched, all of them **ledger and
  pantry test fixtures** (merchant cities on synthetic statements), UI string tests, and archived
  agent definitions. None is a home location.
- Grep across `app/src/main` for `homeLat`, `defaultLat`, `DEFAULT_LOCATION`, `ZoneId.of`,
  `America/*`: the only hits are **doc comments** in `notes/Recurrence.kt`, `workouts/WorkoutGap.kt`,
  and `service/LiveToolbox.kt` using `America/Chicago` and `America/Los_Angeles` as worked examples
  in bug narratives.

`America/Chicago` appears twice in bug-narrative comments describing real observed off-by-five-hours
behaviour, which weakly suggests US Central Time. **That is an inference from a code comment, not a
recorded fact, and it is not a city.** It is recorded here so nobody later mistakes it for a
determination. **Do not guess a city.**

### How to determine it

1. Ask Kevin. One line, done.
2. Or read it at runtime and never hardcode it: the device's `Geocoder` reverse-geocode of the
   current fix already yields locality and admin area, and `location/LocationController` +
   `PlaceController` already exist. This is the correct shape anyway - a personal assistant that
   hardcodes a hometown breaks the moment it travels.

### The general pattern, which is city-independent

US municipal police-incident feeds cluster into two platforms.

**Socrata (SODA).**
- Classic shape: `https://{portal-domain}/resource/{dataset-id}.json`
- SoQL params: `$where`, `$select`, `$order`, `$limit`, `$offset`, `$q`
- Typical police-incident columns: incident number, offense/description, date-time reported,
  block-level address (deliberately truncated to a block, e.g. "100 BLOCK OF MAIN ST"), latitude,
  longitude, district/beat, disposition.
- **Important change:** dev.socrata.com documents a **Version 3.0 (2025)** endpoint format
  `/api/v3/views/{dataset-id}/query.json`, and states "Query requests must be either authenticated
  by a user or marked with a valid application token" - i.e. under SODA3 an **app token is
  required**, not merely recommended. `doc`
  Source: https://dev.socrata.com/docs/endpoints.html
  Whether a given city's portal has migrated, and whether its legacy `/resource/` endpoint still
  answers anonymously, is **per-portal and `unverified`**.

**ArcGIS Hub / ArcGIS Online FeatureServer.**
- Shape: `https://services{n}.arcgis.com/{org}/arcgis/rest/services/{Layer}/FeatureServer/0/query`
- Params: `where=1=1`, `outFields=*`, `f=geojson`, `geometry` + `geometryType` +
  `spatialRel=esriSpatialRelIntersects`, `distance` + `units`, `resultRecordCount`
- Usually keyless for public layers, with a `maxRecordCount` (NIFC's is 2000, `live`) forcing
  pagination via `resultOffset`.
- Confirm capabilities by fetching the layer root with `?f=pjson` first, exactly as done for NIFC
  in §2d. That single request tells you whether `Query` is supported, the record cap, and whether
  distance queries work - before writing any client code.

**How to find whether a specific city publishes one:**
1. Search `{city} open data portal` and look for a `data.{city}.gov` or `{city}.opendata.arcgis.com`.
2. Search that portal for "police incident", "crime incident", "calls for service".
3. Fetch the dataset's API endpoint with a `$limit=1` (Socrata) or `?f=pjson` (ArcGIS) and read the
   real schema rather than the portal's description.
4. Check the dataset's own update cadence field - many are daily-batch, some are weekly, very few
   are near-real-time. A "live incident feed" is rarer than it sounds.

**Honesty note that carries over from §5:** a block-level incident feed is far finer-grained than
FBI CDE and far fresher, but it still does not answer "is this area safe." It answers "these
incidents were reported here recently." That is a materially better answer and it is still a
report of reports.

---

## 7. Consolidated implications for LEGION

**Keys needed (all BYO, all Keystore via the existing `KeyVault` path):**
- NASA FIRMS MAP_KEY (free, no card)
- AirNow API key (free, no card)
- TomTom API key (free, no card) - if traffic is built
- api.data.gov key for FBI CDE (free, no card) - if crime history is built

**Zero keys needed:** NWS, USGS, FEMA, NIFC WFIGS. Four of the six question areas are fully keyless.

**No card is required for any recommended source.** That is the whole reason Google and HERE lose.

**Polling discipline:**
- NWS: no faster than every 30s, and only while something is actually being watched.
- USGS: use the every-minute summary feeds, not the query API.
- AirNow: hourly at most; data only updates hourly and bulk polling is discouraged by the FAQ.
- TomTom: on request only, never on a timer.
- FEMA, NIFC, FBI: on request; these change on a scale of days to years.

**Offline degradation, stated in words** (project rule): every one of these is a network call.
Weather alerts and traffic are the two where stale data is worse than no data. A cached answer must
carry its fetch time out loud.

**Attribution when spoken** (project rule): "the National Weather Service", "the USGS", "FEMA",
"NASA FIRMS", "the National Interagency Fire Center", "AirNow", "TomTom", "the FBI's Crime Data
Explorer".

**Open items, all `unverified`, in priority order:**
1. Which AirNow lat/lon endpoint survives the fall-2026 retirement. Deadline is weeks away.
2. TomTom's actual caching clause, before any ETA is persisted.
3. NWS `severity`/`urgency`/`certainty` enums, from `api.weather.gov/openapi.json`, before an enum
   is hardcoded.
4. OpenFEMA's published rate limit and max `$top` - the docs pages 403'd this tool.
5. Kevin's city. Ask, or resolve at runtime from `Geocoder`.
