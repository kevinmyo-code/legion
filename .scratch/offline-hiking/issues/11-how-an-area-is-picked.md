---
map: offline-hiking
ticket: 11
title: "Decision: how an offline area is picked and named"
type: decision
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-mapbox-offline-facts]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Decision: how an offline area is picked and named

## Question

Kevin says "download the map for <a state park>". Something has to turn that into a box or polygon
to download, and the region gets kept on the phone for weeks. What may produce that shape under
Mapbox's terms, and what does the stored region carry?

## The terms problem (sourced in `.scratch/mapbox-nav/research/01-sdk-facts.md`)

- Product Terms 2.7.2: Temporary Geocodes (the default for Search Box and the Search SDK) may not be
  exported, stored or cached.
- 2.7.1: a Mapbox geocoding result's lat/lng is never shown to the user.
- A tile region whose geometry IS a search result's coordinates, kept for weeks, is arguably storing
  a temporary geocode (reasoned; no clause addresses offline regions directly; research 01 checks).

## Options

| Option | How | Terms | Effort |
|---|---|---|---|
| **A. "Download this view", by hand** | Kevin pans and zooms the map to the park; a frame shows the area and its estimated size; he names it and taps Download. Search may move the camera there first, and is then dropped. | Clean: the box is Kevin's own, the search hit is used transiently to move a camera and never stored. | S |
| B. Search hit to a box | The Search SDK's result plus a fixed radius becomes the region. | Grey under 2.7.2 (stored derivative of a temporary geocode). Permanent geocoding fixes it but costs $5 / 1,000 with a card on file. | S |
| C. OSM boundary | The park's `boundary=protected_area` / `leisure=nature_reserve` polygon from OpenStreetMap (Nominatim or Overpass), ODbL, storable with attribution. | Clean; ODbL attribution. Nominatim's usage policy allows light use with a real User-Agent. | M |
| D. A bundled list of Texas state park boundaries | TPWD's published GIS boundaries, bundled as an asset. | Depends on TPWD's licence (research 03). Texas-only, so clone-and-run elsewhere gets nothing. | M |

## Recommendation

**A in v1; C in v2 for the voice path.** A is the smallest, needs no new source, and is the only
option with no terms question at all. For "download the map for X" by voice (ticket 12), C gives a
real park outline from open data that may be kept. B only if Kevin accepts permanent-geocoding cost.

## The stored region carries

- The name Kevin typed or picked (his words, first-party).
- The geometry (his box, or an OSM polygon with ODbL attribution).
- Download date, size on disk, style and zoom range, last refreshed.
- Never a Mapbox search result's name, address or coordinates.
