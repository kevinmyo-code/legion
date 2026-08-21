---
map: location-intelligence
ticket: 7
title: "TomTom's caching clause, before anything is stored"
type: research
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# TomTom's caching clause, before anything is stored

## Question

`.scratch/hands-and-senses/research/14-location-intel.md` marked TomTom's caching posture
`unverified`, and flagged that Google's equivalent clause is the most restrictive in the market with
only a narrow 30-day lat/lon carve-out.

**This blocks storing an ETA, not calling for one.** [Ticket 06](06-departure-advisor.md) can ship
computing a fresh ETA each time; what it may not do until this is answered is persist one to Room or
to Drive `appDataFolder`.

Establish, from TomTom's own terms:

1. May a Routing API response be cached at all, and for how long?
2. Are coordinates, ETAs and route geometry treated differently?
3. Does storing an ETA in an app-private database count as caching under their terms?
4. Anything about attribution requirements when a result is spoken aloud rather than displayed -
   decision 4 already says "per TomTom", so confirm that satisfies it.

Quote the clauses verbatim with URLs. If a term is genuinely ambiguous, **say so rather than
choosing the convenient reading** - the safe default is not to persist.
