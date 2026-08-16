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
