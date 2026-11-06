---
map: nav-quality
ticket: "13"
title: "Build: recent and frequent destinations, kept by the arrival fix"
type: build
status: built
status-detail: "2026-11-06 on feat/nav-audit, phone-only store; voice forget and the engine copy are ticket 14; needs the phone"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
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

## Built (2026-11-06, `feat/nav-audit`)

- `navigation/resolve/RecentPlaces.kt`: `RecentPlaces` (the store), `RecentSource` (`SourceKind.RECENT`,
  after saved places, before calendar), `PrefsRecentPlacesIo` (the phone's private prefs).
- Kept only on an arrival at a place SEARCH found, with the user's words known and not a "nearest"
  ask; a saved place, a calendar or suggestion place and a contact's address are never kept.
  `MapboxNavController.onArrived` hands the destination and `fix()` to it; the resolver stamps every
  candidate with the normalised phrase (`Candidate.asked`, `NavDestination.asked`).
- Rank: count halved per 30 days since the last arrival. 150 m merge, 50 rows. A damaged store reads as
  none.
- `route_request` logs a recent destination's point (`to=`), like a saved place: both are the
  phone's own fix.
- **Hands path to forget:** Settings, Data and privacy, "Recent places": one tap forgets them all.

### Deferred, named

- **Forget by voice** and **a copy on the engine** are [ticket 14](14-recents-voice-and-engine.md).
  Ticket 05 asked for "Room plus the engine"; this is the phone's prefs only, so a reinstall loses the
  rows (first-party and rebuildable, so nothing is lost that matters). The voice tool waited because
  adding one rewrites `docs/voice.html` and `tools/voice_guide_copy.py`, which `fix/coach-voice` is
  shipping now.

### Owed on the phone

- Search a place by name, drive there, arrive; ask for it again by the same words: the `search` line
  says `source=recent` with no `search` entry in `tried=`, and `ms=` in single digits.
- The Data and privacy row counts it, and one tap forgets it.
