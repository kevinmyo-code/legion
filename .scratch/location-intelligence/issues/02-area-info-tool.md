---
map: location-intelligence
ticket: 2
title: "The area_info tool, with attribution baked in"
type: build
status: built
status-detail: "2026-08-21 - built against live endpoints; owes a run on the phone"
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

## Built - 2026-08-21

`AreaInfo` (WEATHER/QUAKE/FIRE/DISASTER), `CrimeHistory`, `UsStates`, both tools in `LiveToolbox`,
24 tests. 99 voice tools now, guide regenerated with no drift.

**Every endpoint was exercised live with curl, not coded against docs.** The response shapes drove
the parser rather than being inferred - which is the difference between this and the AirNow client
we deliberately did NOT write.

### Findings that only came from hitting the real services

- **USGS puts coordinates as `[lon, lat, depth]`** (GeoJSON order) and its free-text `place` is not
  reliable for a consistent spoken distance - so bearing and distance are computed from
  `geometry.coordinates` instead of read from prose.
- **NIFC needed `IncidentTypeCategory='WF'`** or prescribed burns (`RX`) come back as fires. The
  field carries **no published domain** - the code set is empirically observed, and the comment says
  so rather than implying it is documented.
- **FBI CDE keys its monthly series by `"<Agency Name> Offenses"`** - a dynamic key per agency, found
  by its `" Offenses"` suffix, never hardcoded. And **the latest year present is very often
  incomplete** (trailing nulls), so "most recent complete year" is computed, never assumed. That is
  the ticket's honesty requirement surviving contact with the actual data.
- **`Geocoder.adminArea` returns "Texas", not "TX"**, and both FEMA and FBI filter on the
  abbreviation. Hence `UsStates` - there is no Android API for that conversion.

### Honest simplifications, stated rather than hidden

- **FEMA is filtered by STATE, not county.** Resolving a county to FEMA's numeric `fipsCountyCode`
  needs a lookup table this ticket did not scope. The output names the actual `designatedArea`, so
  nothing is overclaimed as more local than it is.
- Quake radius 200mi / 5 results, fire radius 100mi. Defaults, not findings, and commented as such.
- A named place matching no agency returns **null rather than silently falling back to nearest** -
  answering about the wrong jurisdiction would be worse than not answering.

### AIR is deliberately absent

Not forgotten. AirNow is blocked on [ticket 10](10-airnow-account.md)'s login visit, and the gap is
commented in `AreaInfo` so it reads as a decision.

### Owed on the phone

Real GPS acquisition, actual Gemini tool-calling against these declarations, and whether spoken
output ("6:15pm", agency names) reads sensibly aloud. None of that is testable from here.
