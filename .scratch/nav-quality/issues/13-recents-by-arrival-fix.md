---
map: nav-quality
ticket: "13"
title: "Build: recent and frequent destinations, kept by the arrival fix"
type: build
status: open
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Build: recent and frequent destinations, kept by the arrival fix

From [ticket 05](05-recents-and-frequents.md), resolved 2026-11-06 (Kevin, decision D4: option A).

## What is kept

- **On `arrival` only**, never on a preview or a cancelled trip: the phone's own GPS fix at arrival
  and the words the user asked with (the resolver's normalised phrase), plus a count and the last
  arrival time. First-party, like `tag_place`.
- **Never** a Mapbox result's name, address, coordinates or `mapbox_id` (ToS 2.7.2; D4 chose the fix
  over the id). The label is the user's own words, not the business name.
- One row per (member, phrase); a repeat arrival within about 150 m of the stored fix bumps the count
  and the time and moves the fix to the newest one.
- Private to the member who drove. Forgettable in one instruction ("forget my recent places").

## The source

- `SourceKind.RECENT`, tried after saved places and before calendar.
- Matches like saved places (exact, then contains). Ranking among several: frequency weighted by
  recency, then distance.
- A saved place with the same name wins; a recent never shadows one.

## Proven by NAV_AUDIT

- A `recent` entry in `tried=`: hit rate, and latency in milliseconds against `search`'s second.
- `source=recent` share of all searches; picked rank 1 share up for destinations driven before.

## Verification

- Suite green: the store (arrival keeps, preview does not, the 150 m merge, forget), the source's
  match and order, and its place in the resolver order.
- On the phone: drive somewhere found by search, arrive, ask for it again by the same words, and the
  `search` line says `source=recent` with no `search` entry in `tried=`.
