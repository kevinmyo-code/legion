---
map: mapbox-plus
title: "Mapbox good enough that Google Maps is no longer needed"
charted: 2026-10-23
charted-by: "Kevin + Opus"
effort: "`.scratch/mapbox-plus/`"
tickets: 7
open: 7
status: open
tags: [map]
---

# Mapbox good enough that Google Maps is no longer needed

**Kevin, 2026-10-23:** *"how can we improve our mapbox capabilities to the point where i dont use google
maps anymore"*, then *"right now its always night mode. hard to see sometimes. i like google maps day and
night mode styles theyre easy to see."*

Builds on `.scratch/mapbox-nav/` (ADR 0054). Navigation there is built; its ticket 12, a real drive on
the A25, is still owed. Until this map lands, Matrix mode's Maps entry may open Google Maps (ADR 0062
amendment, 2026-10-23).

## Destination

Kevin drives with LEGION's own navigation by choice, and stops opening Google Maps.

## Tickets

| # | Type | What | Blocked by |
|---|---|---|---|
| 01 | build | Day and night map styles that are easy to read, switching like Google's | - |
| 02 | test | A real drive on the A25 (mapbox-nav 12), with a checklist, before anything else new | 01 |
| 03 | build | Recent destinations and favourites on the nav screen | 02 |
| 04 | build | Traffic on the route, lane guidance, speed limit shown | 02 |
| 05 | decision | Offline maps: which area, how big, when to refresh | 02 |
| 06 | decision | Place search ("tacos near me", open now): Mapbox Search Box vs another POI source | 02 |
| 07 | decision | LEGION-only extras: ETA to Mia, "leave by" from calendar and Canvas, fuel-aware stops | 02 |
