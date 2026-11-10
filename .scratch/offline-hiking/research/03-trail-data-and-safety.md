---
map: offline-hiking
research: "03"
title: "Trail data, imports, phone sensors and safety"
tags: [research]
---

# Trail data, imports, phone sensors and safety

Researched 2026-11-09. `sourced` = stated by the linked page. `reasoned` = my inference from sourced facts.
Some pages returned 403 to direct fetch; those facts come from search-result snippets of the same URL (see Gaps).

## Headline

- TX state park trails: TPWD "official trails" data (statewide KMZ + ArcGIS service) beats OSM for trail lines. `reasoned`
- OSM beats TPWD for POIs (parking, toilets, campsites, viewpoints). OSM trail tagging in sampled parks is thin: no `route=hiking` relations, almost no `sac_scale`, `highway=trailhead` missing in 3 of 4. `reasoned`
- Galaxy A25 5G: compass yes, barometer not listed, no built-in satellite SOS. Plan for a separate SOS device (inReach) or carrier satellite text. `reasoned`

## 1. OSM trail data for Texas state parks

### Tags and community

| Fact | Tag | Source URL |
|---|---|---|
| `sac_scale` values: strolling, hiking (T1), mountain_hiking (T2) ... difficult_alpine_hiking (T6). Ways only; main on `highway=path`/`footway`, also `track` | sourced | https://wiki.openstreetmap.org/wiki/Key:sac_scale |
| `trail_visibility` values: excellent, good, intermediate, bad, horrible, no | sourced | https://wiki.openstreetmap.org/wiki/Key:trail_visibility |
| Global use (taginfo, data to 2026-11-09): `highway=path` 16.6M ways; `sac_scale` 929k ways; `trail_visibility` 938k ways; `informal=yes` 429k ways; `route=hiking` 273k relations; `highway=trailhead` 18.8k nodes | sourced | https://taginfo.openstreetmap.org/api/4/tag/stats?key=highway&value=trailhead (same API for other keys) |
| OSM wiki Texas page lists TPWD state park boundaries ("Not updated frequently") and state park trails ("updated often") as resources; says TPWD GIS info is public domain | sourced | https://wiki.openstreetmap.org/wiki/Texas |
| Texas wiki page lists no Texas-specific forum/mailing list; the "Tagging recommendations for Texas" subpage URL I tried 404'd | sourced | https://wiki.openstreetmap.org/wiki/Texas |
| OSM Contributors page: TPWD GIS team emailed a mapper (2019-09-18): "All data that is available from the TPWD website is in the public domain"; asks attribution "Texas Parks & Wildlife Department, Date" plus an as-is disclaimer | sourced | https://wiki.openstreetmap.org/wiki/Contributors (section Texas Parks & Wildlife Department) |
| Import catalogue: Texas rows are Austin buildings/addresses, Microsoft buildings N. Central TX, Fort Worth, Texas LIDAR, pipelines. No TPWD trail import listed | sourced | https://wiki.openstreetmap.org/wiki/Import/Catalogue |
| So: no evidence of a TPWD trail import into OSM (absence of a catalogue row is not proof) | reasoned | same |

### TPWD trail data

| Fact | Tag | Source URL |
|---|---|---|
| TPWD offers one statewide KMZ of all state park trails, linked from each park's Maps page; "updated periodically" | sourced | https://tpwd.texas.gov/state-parks/park-information/maps/use-the-trails-maps-anytime-anywhere |
| KMZ URL: `https://tpwd.texas.gov/spdest/parkinfo/maps/gis/kmz/texas_state_park_trails.kmz` | sourced | same |
| No GPX, PDF or Avenza mentioned on that page; no licence on that page; site footer says content copyright TPWD unless noted | sourced | same |
| ArcGIS MapServer `Parks/TexasStateParksTrails`: polylines, official trails only, park + trail name + miles, use types (hike, bike, equestrian, multiuse, OHV, paddling); vetted by park staff on rolling basis | sourced | https://tpwd.texas.gov/server/rest/services/Parks/TexasStateParksTrails/MapServer/info/iteminfo?f=pjson |
| Same item: `licenseInfo` = "For informational use only"; "Any trails may be closed or re-routed without warning"; as-is, user assumes risk | sourced | same |
| Per-park KML files also exist (example: Tyler SP, OGC KML 2.2 LineStrings), but the sampled one says "current as of January 30, 2014" | sourced | https://tpwd.texas.gov/state-parks/tyler/gis-files/gis-alltrail-files/view |
| Licence signals conflict: 2019 email = public domain w/ attribution; service metadata = "informational use only". For a personal app bundling a local copy, low risk; for redistributing in a public repo, keep the data out of git and download at build/run time, with attribution | reasoned | two rows above |

### Sample check (Overpass API, run 2026-11-09)

Parks chosen by the researcher as well-known examples across the state, not places anyone in this household has visited. Method: Overpass query per park boundary (found by name; tagging varies: Palo Duro and Big Bend Ranch are `boundary=protected_area`, Enchanted Rock `leisure=park`, Lost Maples `leisure=nature_reserve`). Counts are OSM way segments, not distinct trails. "Named" and "surface" counted on `highway=path` only.

| Park | path | footway | track | sac_scale | trail_vis | informal=yes | named path | route=hiking rel | trailhead | parking | toilets | drinking_water | viewpoint | camp_site | camp_pitch |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Palo Duro Canyon SP | 48 | 12 | 23 | 0 | 0 | 0 | 30 | 0 | 0 | 21 | 8 | 0 | 8 | 5 | 18 |
| Enchanted Rock SNA | 86 | 1 | 15 | 0 | 13 | 60 | 20 | 0 | 0 | 9 | 8 | 1 | 0 | 3 | 3 |
| Big Bend Ranch SP | 34 | 36 | 206 | 4 | 0 | 0 | 27 | 0 | 3 | 33 | 2 | 0 | 4 | 49 | 3 |
| Lost Maples SNA | 2 | 32 | 9 | 0 | 0 | 1 | 0* | 0 | 0 | 6 | 7 | 0 | 3 | 7 | 30 |

\* Lost Maples trails are mostly `highway=footway`; the 0 is an artifact of counting names on `path` only.

| Fact | Tag | Source URL |
|---|---|---|
| Counts above from Overpass API, OSM data timestamp 2026-11-10T03:19Z | sourced | https://overpass-api.de/api/interpreter |
| Trails exist as ways in all 4, but no `route=hiking` relations anywhere: app must stitch named ways into "trails" itself, or use TPWD trail names | reasoned | table |
| Enchanted Rock: 60 of 86 paths `informal=yes` (social trails on granite). App should hide or dim `informal=yes` by default | reasoned | table |
| Difficulty metadata (`sac_scale`) near-absent; do not build difficulty features on it for TX | reasoned | table |
| Big Bend Ranch: backcountry routes largely `highway=track` (206), so a trail layer must include tracks | reasoned | table |

## 2. GPX/KML import

| Fact | Tag | Source URL |
|---|---|---|
| GPX 1.1 namespace `http://www.topografix.com/GPX/1/1`; root `<gpx>` requires `version="1.1"` and `creator`; children in order: metadata, wpt*, rte*, trk*, extensions | sourced | https://www.topografix.com/GPX/1/1/ |
| `wpt` requires `lat`, `lon`; optional ele, time, name, desc, sym etc. `rte` holds `rtept`; `trk` holds `trkseg` holding `trkpt` (new segment when fix lost) | sourced | same |
| KML 2.2 namespace `http://www.opengis.net/kml/2.2`; Placemark + Point/LineString; coordinates are lon,lat[,alt] (reverse of GPX attributes) | sourced | https://developers.google.com/kml/documentation/kml_tut |
| KML MIME `application/vnd.google-earth.kml+xml`, KMZ `application/vnd.google-earth.kmz` (both IANA-registered) | sourced | https://www.iana.org/assignments/media-types/application.csv |
| `application/gpx+xml` is NOT in the IANA registry; `application/geo+json` is (RFC 7946) | sourced | https://www.iana.org/assignments/media-types/application.csv |
| AllTrails: "Hit the Trail" is Plus/Peak only; within it "Export map file" downloads GPX or other types; export is on the website, not app | sourced (snippet) | https://support.alltrails.com/hc/en-gb/articles/37226966327444-Hit-the-Trail-Button ; https://support.alltrails.com/hc/en-us/articles/37230403315476-Downloading-files-from-AllTrails |
| So GPX export of AllTrails trails effectively needs a paid tier | reasoned | above |
| AllTrails Terms: personal non-commercial use; no copy/distribute/republish of Materials (incl. trail maps); no scraping; map data partly from an OSM-derived database | sourced | https://www.alltrails.com/terms |
| Gaia GPS: exports tracks, routes, areas, waypoints, folders as GPX, KML or GeoJSON from gaiagps.com | sourced (snippet) | https://help.gaiagps.com/hc/en-us/articles/230141967-Export-data-from-gaiagps-com |
| CalTopo web export: GeoJSON, KML, GPX, GPSIO; mobile can export files. Only tier note: elevation in GPX needs Pro | sourced | https://training.caltopo.com/all_users/import-export/export |
| TPWD: KMZ statewide + per-park KML; no GPX offered. App needs a KML/KMZ parser for TPWD data | sourced + reasoned | https://tpwd.texas.gov/state-parks/park-information/maps/use-the-trails-maps-anytime-anywhere |
| Android share sheet: register `ACTION_SEND` intent filter with MIME; file arrives as `EXTRA_STREAM` content URI; read via `IntentCompat.getParcelableExtra(..., Uri::class.java)`; doc warns "the wrong MIME type might be set" and to avoid `*/*` unless you can handle anything | sourced | https://developer.android.com/training/sharing/receive |
| Intent filters for `ACTION_VIEW` use `<data android:mimeType=...>` / scheme; matching per Intents guide | sourced | https://developer.android.com/guide/components/intents-filters |
| Because gpx+xml is unregistered, senders (file managers, Drive, mail) often label .gpx as `application/octet-stream`, `text/xml` or `application/xml`. Register filters for `application/gpx+xml`, `application/vnd.google-earth.kml+xml`, `application/vnd.google-earth.kmz`, plus `application/octet-stream`/`text/xml` and sniff content (root element `<gpx>` vs `<kml>`, ZIP magic for KMZ). Also offer an in-app SAF picker (`ACTION_OPEN_DOCUMENT`) as the reliable path | reasoned | above rows |

## 3. Offline trailhead/POI search

| Fact | Tag | Source URL |
|---|---|---|
| `highway=trailhead`: de facto; node standard (area allowed); recommended `name`, `operator`, `access`, `informal`; optional `fee`, `parking`, `drinking_water`, `toilets` | sourced | https://wiki.openstreetmap.org/wiki/Tag:highway%3Dtrailhead |
| Other POI tags to pull: `amenity=parking`, `amenity=toilets`, `amenity=drinking_water`, `tourism=viewpoint`, `tourism=camp_site`, `tourism=camp_pitch`, plus `tourism=information` (park HQ nodes seen in results) | reasoned | sample table above |
| Sampled parks: parking/toilets/campsites well mapped; trailheads mostly absent; drinking_water near-absent. Synthesize "trailheads" from trail-way endpoints near parking, or from TPWD trail starts | reasoned | sample table |
| Overpass public instance policy: < 10,000 queries and < 1 GB/day is "assumed" OK; for regular use divide by 100; set User-Agent; after 429/406 wait 30 s; heavy use = self-host | sourced | https://wiki.openstreetmap.org/wiki/Overpass_API |
| During this research overpass-api.de returned "502 ... at capacity" on 4 of ~8 queries; succeeded on retry with backoff | sourced (observed) | https://overpass-api.de/api/interpreter |
| Feasible: one Overpass query per park bbox/area at download time (when online), store results in local SQLite FTS for offline name search. Not viable as live on-trail search | reasoned | above two rows |
| Alternative bulk source: Geofabrik Texas extract `texas-latest.osm.pbf`, 693 MB (2026-11-08 data); filter with osmium on desktop to a small per-park POI file shipped to phone | sourced (size) + reasoned | https://download.geofabrik.de/north-america/us/texas.html |
| OSM data is ODbL: attribution required, derived DB share-alike if distributed | sourced | Overpass response header `copyright` field (https://overpass-api.de/api/interpreter) |

## 4. Samsung Galaxy A25 5G (US) sensors

| Fact | Tag | Source URL |
|---|---|---|
| Samsung official sensor list: Accelerometer, Fingerprint, Gyro, Geomagnetic, Light, Virtual Proximity Sensing. Geomagnetic = compass: yes | sourced | https://www.samsung.com/ph/smartphones/galaxy-a/galaxy-a25-5g-blue-256gb-sm-a256ezkhphl/ |
| Barometer not in Samsung's list (one third-party site, Phonescoop, claims one). Lean: no barometer. Check `SensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)` at runtime; fall back to GNSS/DEM altitude | sourced + reasoned | above; https://phonescoop.com/phones/phone.php?p=7006 |
| Location tech: GPS, GLONASS, BeiDou, Galileo, QZSS | sourced | Samsung PH page above |
| L1-only vs L5: no official source found. Check on device via `GnssStatus.getCarrierFrequencyHz()` (L5 ~1176.45 MHz) | reasoned | https://developer.android.com/reference/android/location/GnssStatus |
| Battery 5000 mAh typical (rated 4,905 mAh); Exynos 1280 octa-core | sourced | Samsung PH page above |
| US business spec sheet: Octa-core 2.4 + 2.0 GHz, 5000 mAh | sourced | https://image-us.samsung.com/SamsungUS/samsungbusiness/mobile/phones/galaxy-a/HHP449741_A25_5G_SpecSheet.pdf |
| US model numbers SM-A256U / SM-A256U1 | sourced | https://www.samsung.com/us/business/smartphones/galaxy-a25/buy/galaxy-a25-5g-128gb-unlocked-sm-a256uzkdxaa |
| **No built-in satellite messaging or SOS.** Samsung spec lists none; Skylo certified-device list has no Galaxy A model (S25/S26, Z Fold7/8, Z Flip7/8 only) | sourced + reasoned | https://www.skylo.tech/certified-devices |
| Samsung satellite (Skylo, carrier-dependent): Verizon Satellite SOS + messaging on Galaxy S25 series, Z Fold7, Z Flip7 (also Pixel 9/10) via Google Messages; < 140-char SMS | sourced (snippet) | https://www.verizon.com/support/satellite-faqs/ ; https://www.verizon.com/articles/Smartphones/verizon-satellite-messaging/ |
| Pixel Satellite SOS: all Pixels since Pixel 9 except 9a; US included; needs Google Messages default + clear sky | sourced | https://support.google.com/pixelphone/answer/15254448 |
| iPhone Emergency SOS via satellite: iPhone 14 or later; free 2 years after activation; US included | sourced | https://support.apple.com/en-us/101573 |
| T-Mobile T-Satellite (Starlink direct-to-cell, works on non-satellite-chip phones): T-Mobile list shows "Samsung Galaxy A25 SE" as eligible; plain A25 5G (SM-A256U) neither confirmed nor excluded; meaning of "SE" unresolved | sourced (snippet) + gap | https://www.t-mobile.com/support/coverage/satellite-support |
| T-Mobile (Nov 2025): Text to 911 via satellite free to anyone with a compatible phone, any carrier, enroll required; full T-Satellite messaging $10/mo for non-customers; text only, US only | sourced (snippet) | https://www.t-mobile.com/news/network/t-mobile-text-to-911-available-for-everyone |
| Action: run T-Mobile IMEI checker on the actual phone; if eligible, free 911 text is the cheapest backcountry SOS layer | reasoned | above |

## 5. Safety baseline

| Fact | Tag | Source URL |
|---|---|---|
| inReach Mini 2: two-way text, tracking, weather over Iridium; SOS goes to Garmin IERCC / Garmin Response (24/7 staffed), interactive | sourced (snippet) | https://www.garmin.com/en-US/newsroom/press-release/outdoor/new-garmin-inreach-mini-2-delivers-up-to-30-days-of-global-satellite-communication-emergency-services-and-enhanced-location-tracking/ |
| Mini 2 battery: up to 14 days (10-min tracking, full sky), up to 30 days (30-min), ~4 days under moderate tree cover | sourced (snippet) | https://www8.garmin.com/manuals/webhelp/GUID-802DEF62-EBB2-463C-8C51-C58FB29F89E3/EN-US/GUID-D9DEA9AC-8107-4A2F-9A4E-E5A0B4A7EC3C.html |
| inReach Messenger: up to 28 days default; pairs with Garmin Messenger app (auto Wi-Fi/cell/satellite); SOS button; launch MSRP $299.99 (2022) | sourced (snippet) | https://www.garmin.com/en-US/newsroom/press-release/outdoor/garmin-inreach-messenger-easy-to-use-satellite-communicator-provides-messaging-and-security-when-outside-of-cellular-coverage/ |
| Any satellite use needs an active subscription | sourced (snippet) | https://www8.garmin.com/manuals/webhelp/GUID-802DEF62-EBB2-463C-8C51-C58FB29F89E3/EN-US/inReach_Mini_2_OM_EN-US.pdf |
| Consumer plans (Garmin blog, 2026-10-20): Premium (unlimited text, tracking), Standard (150 texts), Essential (50 texts), Enabled (SOS only, rest pay-as-you-go), Suspend. All include SOS. Reactivation incurs activation fee | sourced | https://www.garmin.com/en-US/blog/outdoor/overview-inreach-subscription-plans/ |
| Prices (not from an official current sheet): Enabled ~$7.99/mo, Essential ~$14.99/mo, Standard ~$29.99/mo, activation ~$39.99; no annual contracts since late-2024 change. Verify before buying | reasoned (secondary) | https://www.treelinereview.com/news/explaining-new-garmin-inreach-subscription-plans ; https://www.garmin.com/en-US/p/837461/ |
| inReach vs this phone: works on any carrier/none, global Iridium, staffed 2-way SOS, weeks of battery, physical SOS button. A25 has none of those built in | reasoned | rows above + section 4 |
| TPWD: "Letting someone know your plan and the time you should be back means that, if you become lost, people know where to look"; don't rely on phone maps in backcountry | sourced | https://tpwd.texas.gov/newsmedia/releases/?req=20220628b |
| TPWD heat safety: "Take a map and tell someone your hiking route" | sourced | https://tpwd.texas.gov/state-parks/park-information/safety/heat-safety-info |
| NPS: leave plan with a reliable person not on the trip, not with the park; include party, route, camps, return time, and "If party has not returned to (location) by (time) on (date), contact (agency) at (phone)" | sourced (snippet) | https://www.nps.gov/articles/upload/NPS-Trip-Planning-Guide-Package-1.pdf ; https://www.nps.gov/gaar/planyourvisit/backcountry-trip-plan.htm |
| App feature idea: generate this trip plan (route, park, overdue time, who to call) and share via SMS before leaving signal | reasoned | above |

## 6. Sunset/daylight

| Fact | Tag | Source URL |
|---|---|---|
| NOAA calc is pure math from Meeus *Astronomical Algorithms*, ~1 min accuracy within +/-72 deg lat, downloadable spreadsheets: fully computable offline (no network) | sourced + reasoned | https://gml.noaa.gov/grad/solcalc/calcdetails.html |

## Gaps

- Direct fetch 403'd; facts taken from search snippets of these URLs: AllTrails help (37230403315476, 37226966327444), Gaia help 230141967, T-Mobile satellite-support / satellite-phone-service / text-to-911 news, Garmin plan pages (JS-rendered, no prices).
- Gaia GPS: whether export needs a paid tier; Gaia terms on reuse of exported files. Not verified.
- CalTopo terms on reuse of exported data. Not checked.
- Garmin official current US plan prices and activation fee. Only secondary/older figures.
- Galaxy A25 5G L5 support: no official source; Exynos 1280 Samsung page is retired (no GNSS line).
- Galaxy A25 5G (SM-A256U) T-Satellite / Text-to-911 eligibility: check T-Mobile IMEI checker on device.
- Barometer: official list omits it; confirm with runtime sensor check.
- TPWD licence: 2019 email (public domain) vs ArcGIS "informational use only". Email StateParksMaps@tpwd.texas.gov if redistributing.
- TPWD statewide KMZ: not downloaded/inspected (size, attributes, freshness).
- OSM sample is 4 parks only; small/urban state parks may differ. Texas tagging subpage not found.
