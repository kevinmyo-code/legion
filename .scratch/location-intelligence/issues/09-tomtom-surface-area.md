---
map: location-intelligence
ticket: 09
title: "The rest of the TomTom surface area"
type: grilling
status: kiv
status-detail: "Parked by Kevin 2026-08-21 - inventory now, decide later"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# The rest of the TomTom surface area

## Why this is parked, not queued

Kevin, 2026-08-21, on seeing what his Evaluation key actually enables: *"theres a lot more tings
that we can do with tom tom it seems. lets put that KIV and see what else we can add later."*

**KIV, deliberately.** Settled decision 4 chose TomTom for ONE thing - traffic-aware ETA - and this
map's destination is hazard awareness plus a departure advisor. Everything below is genuinely
interesting and none of it is on the way to that destination. Parked so the inventory is not lost
and the frontier is not widened.

## What the key already enables

Read off Kevin's own key page, 2026-08-21. **All of it is live on the free Evaluation tier today** -
no further signup, no card.

### Plausibly useful to LEGION

| API | What it could do here | Why it is interesting |
|---|---|---|
| **Reverse Geocoding** | Coordinates to a place name | LEGION uses Android's `Geocoder`, which is **flaky and sometimes returns nothing at all** - the reason the opener says "location unknown" rather than guessing. A second source could make "where am I" answerable more often |
| **Places Search / Search** | "find a gas station", "nearest parts store" | The fleet aspect has no way to answer this today. Pairs naturally with the garage and maintenance work |
| **Traffic Incidents** | Closures and incidents near a route | Sharper than an ETA number: *why* the drive is slow. Attribution rules from decision 6 apply unchanged |
| **Reachable Range** | How far on the fuel that is left | Genuinely novel for the fleet aspect, and it needs OBD fuel data LEGION already reads |
| **Waypoint Optimization** | Order a multi-stop errand run | Fits the lists and calendar work more than the fleet work |
| **Snap to Roads** | Clean up a recorded GPS track | Would improve `Drive` records, which currently store raw fixes |

### Probably not

- **EV Charging Stations Availability** - the fleet is internal combustion.
- **Map Display / Map Assets / Styles Upload** - LEGION is voice-first and dropped Mapbox
  deliberately. A rendered map is a different product.
- **Geofencing API** - **already decided against.** Settled decision 9 uses the ANDROID OS
  geofencing API: free, on-device, no network round trip, no key. TomTom's server-side geofencing
  would be strictly worse for this use.
- **Location History, Notifications API** - both imply sending Kevin's movements to a vendor and
  storing them there. That is the opposite of this app's posture (CLAUDE.md §7: no Kevin-hosted
  anything, data on-device and in his own Drive). Would need a real decision, not a default.
- **Batch Search, Matrix Routing, Orbis tiles, Assets, MCP Server** - server-scale or
  visualisation features with no voice-assistant use.

## What to decide when this is picked up

1. **Which of the six plausible ones earn a tool.** Every tool costs prompt budget on every turn;
   the toolbox is already large enough that ticket 15's parent map had to argue about it.
2. **Whether Reverse Geocoding replaces or merely backs up `Geocoder`.** A second source that
   disagrees with the first is a new problem, not a fix.
3. **The rate budget.** 20,000 free calls a month is generous for on-request ETA and much less
   generous if six tools share it. Count before adding.
4. **Whether any of this changes the no-persist rule** from [ticket 07](07-tomtom-caching.md).
   A search result is not an ETA and may carry different terms.

## Note

Nothing here is blocked. It is parked. It becomes takeable when Kevin says so - or sooner, if one of
these turns out to be the cheapest fix for a problem another map is stuck on. Reverse Geocoding
against the `Geocoder` gap is the likeliest of those.
