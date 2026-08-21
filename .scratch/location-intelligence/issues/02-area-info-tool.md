---
map: location-intelligence
ticket: 2
title: "The area_info tool, with attribution baked in"
type: build
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-background-location]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# The area_info tool, with attribution baked in

## What to build

**ONE tool, `area_info`, with a category parameter** (settled decision 1). Not five tools - five
would spend five slots of prompt budget on one idea.

Categories: `weather` (NWS alerts), `quake` (USGS), `fire` (NIFC WFIGS), `air` (AirNow - needs [ticket 10](10-airnow-account.md)'s login visit for parameter names and the hourly cap), `disaster` (FEMA declarations).

**Output is pre-formatted with the source named in the string** (settled decisions 6 and 12) -
`"NWS: Tornado Warning until 6:15pm"`, not raw JSON with a hope that the model credits it.
Attribution becomes structural rather than a prompt rule, and prompt rules are the weakest lever
this codebase has.

## Source rules, already settled - do not re-derive

- **USGS: the real-time GeoJSON summary feeds, never `fdsnws/event/1/query`.** USGS steers automated
  clients there explicitly; they update every minute.
- **NIFC WFIGS, not NASA FIRMS.** NIFC gives `IncidentName`, `IncidentSize`, `PercentContained` -
  speakable. A FIRMS pixel is a thermal anomaly and, if ever used, is spoken as "satellite heat
  detection", never as a fire.
- **NWS needs a `User-Agent` header** identifying the app; it is required in place of a key.
- **FEMA is a declaration, not a hazard.** Context after the fact, never phrased as an alert.
- **AirNow: `/aq/observation/current/ziplatlong/`, NOT `/aq/observation/latLong/current/`.** The
  second retires 2026-09-30 and the two paths differ only in word order - easy to mistype into the
  dead one. AirNow's terms also forbid altering the data, so the AQI number and its category name
  pass through **verbatim**; the model may not round or rephrase them, and the "preliminary data"
  disclaimer must be surfaced in one spoken clause. Fetch **hourly**, not on the 15-minute hazard
  tick - the data only updates hourly, 10-30 min past.
- **"Here" is the live GPS fix with NO fallback** (settled decision 14). With no fix the tool says
  it cannot check and checks nothing - it must never quietly use a last-known or a home place.

## Also here: the crime tool

`get_reported_crime_history` (settled decision 7). Separate from `area_info` because it is a
different shape of answer - a historical count bound to a named agency and year.

**`is_area_safe` is never built.** The tool description states, in words, that the data is
agency-level rather than neighborhood, roughly 13 months stale, voluntarily reported and incomplete,
and **does not answer whether a place is safe**. Asked that question the assistant says so plainly,
then offers the figure. **The refusal is the feature.**

## Verification

- Unit tests on the formatting: every category's string carries its source name, and an empty result
  reads as "nothing reported", never as a blank.
- Live calls to each keyless endpoint from the phone.
