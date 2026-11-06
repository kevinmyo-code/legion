---
map: nav-quality
ticket: "05"
title: "Decision: recent and frequent destinations as a resolver source"
type: decision
status: open
blockers: ["02"]
blocked-by: ["[[02-what-the-audit-may-keep]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Decision: recent and frequent destinations as a resolver source

Absorbs mapbox-plus ticket 03 ("recent destinations and favourites"), which said what to show and
not how to keep it within Mapbox's terms.

## Question

Google ranks by personal history. LEGION has saved places only. A recent-destination source needs
something stored, and a Mapbox search hit may not be stored (2.7.2).

| Option | What is stored | Terms | Trade-off |
|---|---|---|---|
| A. Arrival fix | On `arrival`, the phone's own GPS fix plus the phrase Kevin said | first-party, like `tag_place` | the label is Kevin's words, not the business name |
| B. Feature ID | the `mapbox_id`, re-retrieved on use | 2.7.6 allows Feature IDs | one retrieve per use; needs the network |
| C. Both | A for coordinates, B for a fresh name and hours | as above | most code |

Where it lives: Room plus the engine, private to the member who drove (ADR 0052 and ADR 0075's
pattern). Forgettable in one instruction.

## Recommendation

C. The ranking is recency times frequency, and it is a source placed after saved places and before
calendar.

## Proven by NAV_AUDIT

- A new `recent` source in `tried=`: its hit rate, and its latency in milliseconds where `search`
  takes about a second.
- `answered_by.recent` share of all searches.
- Picked rank 1 share up for destinations driven before.

A build ticket opens when this resolves.
