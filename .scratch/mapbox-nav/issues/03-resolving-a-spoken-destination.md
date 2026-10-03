---
map: mapbox-nav
ticket: "03"
title: "Resolving a spoken destination"
type: decision
status: resolved
status-detail: "Kevin: places, calendar, contacts, then search"
blockers: ["01"]
blocked-by: ["[[01-sdk-facts]]"]
open-blockers: 0
ready: false
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

## Ruled 2026-10-03 (Kevin), the rest still open

**Read back only when unsure.** A saved place or one clear hit starts immediately and says where
it is going. Several plausible hits: the assistant names the top pick with its distance and waits for
a yes. Still open here: resolution order (saved places, calendar, contacts, search) and caching.

## Answer (2026-10-03)

**Kevin: saved places, then calendar, then contacts, then Mapbox search.** First match that is
unambiguous wins.

1. **Saved places** (`tag_place` labels: "home", "work", "the gym"). Exact or close label match.
   Coordinates come straight from the place, no search.
2. **Calendar event locations** ("my next appointment", "the dentist", an event title). The
   event's location text is then resolved through search at trip time. An event with no location
   says so in words; it never guesses one.
3. **Contact addresses** ("Mia's mom"). The address string is resolved through search at trip
   time. No address on the contact is said in words.
4. **Mapbox search**, through the Search SDK only (ToS 2.9.1, research 01), proximity-biased by
   the live fix. Covers addresses, businesses, "nearest X", and "X along the route" (`via`).

- **Read back only when unsure** (ruled earlier the same day): a saved place or one clear hit
  starts and says where it is going; several plausible hits, the assistant names the top pick with
  its distance and waits for a yes.
- **Nothing from Mapbox is stored** (ToS 2.7.2 / 2.10.1). A saved place stays a GPS spot from
  `tag_place`. "Save this search result as a place" would need permanent geocoding: fog.
- Calendar and contacts are first-party data Kevin keeps, read at trip time; the address string is
  used and dropped, never written anywhere new.
