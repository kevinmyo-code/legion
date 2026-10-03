---
map: mapbox-nav
ticket: "03"
title: "Resolving a spoken destination"
type: decision
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-sdk-facts]]"]
open-blockers: 0
ready: true
tags: [ticket]
---

# Resolving a spoken destination

## Question

"Take me to X" names X in words. What turns words into coordinates, in what order?

- Saved places (`tag_place` labels) first? "Home", "work", "the gym".
- Calendar event locations ("my next appointment").
- Contacts' addresses.
- Mapbox Search vs the Geocoding API, proximity-biased by the live fix. Category search ("nearest
  gas station") and search ALONG the route.
- Ambiguity: two matches, a vague name. Does the assistant read back the top pick before routing,
  always, or only when unsure? A wrong destination confirmed silently is the expensive failure.
- Caching: temporary vs permanent geocoding terms (01).
