---
map: nav-quality
ticket: "07"
title: "Build: a faster route and incidents ahead, mid-trip"
type: build
status: open
blockers: ["01"]
blocked-by: ["[[01-baseline]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Build: a faster route and incidents ahead, mid-trip

Google offers "a faster route is available" and warns of a crash ahead. The SDK tracks continuous
alternatives (every 5 minutes by default) and refreshes the route every 5 minutes when
`enableRefresh` is true. Mapbox documents no faster-route API (sourced,
`rerouting-and-refresh` guide). LEGION shows neither.

## Change

1. On `onRoutesChanged` with reason `alternative`, compare each alternative's duration with the
   primary's remaining duration. When one is faster by at least max(3 min, 10%), show it on the
   sheet with a one-tap switch (`takeAlternative`, the existing controller verb, ADR 0035).
2. Speak it as a raise. **It must pass the sec 7 compulsion test:** (a) anchored to the SDK's own
   numbers, (b) actionable now (say "take it"), (c) nothing about engagement, (d) silenced forever
   by "stop suggesting routes". Muted turn cues mute it too.
3. Closures and incidents on the route ahead (`hasUnexpectedUpcomingClosures`, leg `incidents`):
   one spoken line, once per incident.
4. Audit: `route_update` grows `faster_s=` (the best alternative's saving), and a new
   `route_offer offered=... taken=...` line.

## Proven by NAV_AUDIT

- ETA error at arrival: the abs p95 shrinks. Trips that took the offer arrive closer to their
  first ETA.
- Offer take-up rate. Below 20% after two weeks means the threshold is wrong, or the offer is noise.
