---
map: nav-quality
ticket: "02"
title: "Decision: what NAV_AUDIT may keep under Mapbox's terms"
type: decision
status: resolved
status-detail: "2026-11-06, Kevin: the recommendation, all of it; built on feat/nav-audit"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Decision: what NAV_AUDIT may keep under Mapbox's terms

## Question

The audit has to be useful without breaking the Mapbox Product Terms (2026-10-20). See
`research/01-legion-vs-google-maps.md` section 4.

| Field | Today | Clause | Options |
|---|---|---|---|
| Mapbox result name / address | never logged | 2.7.2: not "store, or cache Temporary Geocodes" | (a) keep never; (b) log `mapbox_id` (2.7.6 allows storing Feature IDs) |
| Destination coordinates | rounded to 3 dp (~110 m) | 2.7.2 for a search hit; 2.10.1 for a route | (a) keep; (b) only for first-party destinations (`to_src` is not `search`); (c) crow distance only |
| Route distance and ETA | logged | 2.10.1: not "store results from any request to a Navigation API" | (a) keep, the ETA-error metric needs them; (b) keep only the error (elapsed minus first ETA), drop absolute values |

## Recommendation

- Names: (b), the `mapbox_id`.
- Coordinates: (b).
- ETAs: (a), with the risk accepted in words, as mapbox-nav ticket 13 accepted the vehicle clause.

The audit's purpose is diagnostics on the household's own engine. It is never shared, which is the
spirit of every clause above, but not their letter. That gap is Kevin's call.

## Builds

A build follows if anything other than "keep as built" is chosen: `NavAudit.topJson` and
`routeRequest` change, and `NavAuditTest` pins the new shape.

## Resolution (Kevin, 2026-11-06: "ok for navigation. all of it")

- **Names: (b).** A `search` hit in `top=` carries `"id"`, its `mapbox_id`; never its name or address.
- **Coordinates: (b), first-party only.** `to=` is the rounded point only when the destination is a
  saved place (the household's own GPS fix). A search hit, and a calendar, suggestion or contact
  address that Mapbox geocoded, logs `to=none`; a search destination logs `to_id=<mapbox_id>`.
  The origin (`from=`, the phone's own fix) and `crow_m` stay.
- **ETAs: (a).** Distance, ETA and typical duration stay; the 2.10.1 risk is accepted in words
  (`memory/library/decisions.md`, 2026-11-06).
- **Built:** `NavAudit.topJson`, `NavAudit.routeRequest`, `Candidate.mapboxId`,
  `NavDestination.mapboxId`; `NavAuditTest` pins the shape.
