---
map: offline-hiking
ticket: 03
title: "Research: trail data, GPX imports, the A25's sensors, and safety"
type: research
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Research: trail data, GPX imports, the A25's sensors, and safety

## Question

How good is OSM trail data in Texas state parks, judged generally; what GPX/KML sources exist and
on what terms; what can the A25 sense; and what does it lack for safety?

## Answer

Findings: [research/03-trail-data-and-safety.md](../research/03-trail-data-and-safety.md) (2026-11-09).

- OSM, sampled on four well-known parks: paths and tracks exist in all; `route=hiking` relations
  none; `sac_scale` almost never; `highway=trailhead` mostly missing; many paths tagged
  `informal=yes`. Parking, toilets and campsites well mapped. So: trails on the map are good,
  difficulty data is not, and "find the trailhead" offline needs his own waypoints or the park's GPX.
- TPWD publishes official trails (statewide KMZ and ArcGIS service, no GPX); its licence wording is
  mixed (public domain with attribution in a 2019 letter, "informational use only" in metadata).
- GPX MIME type is not IANA-registered: catch generic types, sniff the content, and add an in-app
  file picker. AllTrails GPX export is behind its paid tier and its terms ban reuse; Gaia GPS and
  CalTopo export GPX and KML.
- Offline POI search: no Mapbox option (research 02); pull a park's OSM POIs once while online.
- A25 5G: compass yes, no barometer, GPS/GLONASS/BeiDou/Galileo/QZSS, 5000 mAh. **No built-in
  satellite SOS.** T-Mobile satellite text-to-911 eligibility unconfirmed (check IMEI).
- inReach: Iridium SOS to a staffed centre; the real backcountry answer.
- NOAA's sunrise/sunset maths runs offline to about a minute.
