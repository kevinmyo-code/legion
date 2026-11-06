---
map: nav-quality
title: "Search and routing close to Google Maps, measured"
charted: 2026-11-06
charted-by: "Kevin + Opus"
effort: "`.scratch/nav-quality/`"
tickets: 14
open: 11
status: open
tags: [map]
---

# Search and routing close to Google Maps, measured

**Kevin, 2026-11-06:** *"lets improve the navigation. do we have a way to audit what routes were called,
its performance etc? my goal is to have the search and route etc as good as googlemaps"*

Builds on `.scratch/mapbox-nav/` (ADR 0054: Mapbox is LEGION's own navigation) and
`.scratch/mapbox-plus/` (Kevin stops opening Google Maps). This map adds the measuring stick and a
ranked list of what to change, each change with the number that proves it.

## Destination

Every change to search or routing is judged by `NAV_AUDIT` numbers from real use, not by feel:
search success, zero-result phrases, picked rank, per-source latency, route latency, reroute rate,
ETA error. The ranked improvements below have moved those numbers against a recorded baseline.

## What exists (built 2026-11-06, `feat/nav-audit`)

- `navigation/audit/NavAudit.kt`: one `NAV_AUDIT` line per `search`, `pick`, `resolve`,
  `route_request`, `trip_start`, `route_update`, `reroute`, `off_route`, `arrival`, `trip_end`;
  cue outcomes counted on `trip_end`. Same switches and engine spool as `LIVE_AUDIT`.
- `tools/nav_audit_report.py`: reads `adb logcat -v threadtime -s NAV_AUDIT` or a downloaded batch.
- Never logged: a Mapbox result's name or address (Mapbox Product Terms 2.7.2), the token, cue text.

## Research

[LEGION against Google Maps, and what the terms allow](research/01-legion-vs-google-maps.md):
Google Places cannot feed a Mapbox map (Google Maps Platform Service Specific Terms 14.2); the
Places UI Kit exception (15.1) still forbids exporting its content (15.3(c)). Most of the gap that
LEGION can close is in its own code. Google's lead in POI data and crowd traffic is data that no
term lets LEGION borrow.

## Ranked improvements (impact against effort)

| Rank | Ticket | Impact | Effort | The NAV_AUDIT number that proves it |
|---|---|---|---|---|
| 1 | [03](issues/03-search-in-one-request.md) One request for text search | High | S | `search` source p50/p95 down; results per search up |
| 2 | [04](issues/04-spoken-name-matching.md) Fuzzy, brand and "near work" matching | High | S-M | zero-result rate and abandoned asks down |
| 3 | [05](issues/05-recents-and-frequents.md) Recent and frequent places as a source | High | M | share answered locally; repeat-trip latency in ms |
| 4 | [06](issues/06-entrance-and-arrival-quality.md) Route to the entrance; measure wrong picks | Med | S | re-search within 2 min of a start; end gap |
| 5 | [07](issues/07-faster-route-and-incidents.md) Faster route and incidents mid-trip | Med | M | ETA error abs p95 down |
| 6 | [08](issues/08-open-now-and-rating.md) Open now and rating in "near me" ranking | Med | S-M | dismissed rate and rank > 1 on category searches |
| 7 | [09](issues/09-lanes-and-missed-turns.md) Lanes on the banner (mapbox-plus 04), measured | Med | M | off-route reroutes per guided hour down |
| 8 | [10](issues/10-a-second-poi-source.md) A second POI source on the engine | Unknown | L | zero-result phrases that the new source answers on replay |

## Tickets

| # | Type | What | Blocked by |
|---|---|---|---|
| 01 | test | Baseline: two weeks of NAV_AUDIT on the A25, the mapbox-plus 02 drive included | - |
| 02 | decision | What NAV_AUDIT may keep under Mapbox's terms (names, ids, coordinates, ETAs) | - |
| 03 | build | One request for text search (`forward`), five results, ETA per result | 01 |
| 04 | build | Spoken-name matching: fuzzy, brand, "X near a place I know" | 01 |
| 05 | decision | Recent and frequent destinations: what is stored, and how | 02 |
| 13 | build | Recent and frequent destinations, kept by the arrival fix | - |
| 14 | build | Forget recents by voice; decide their engine copy | 13 |
| 06 | build | Route to the entrance, and measure wrong picks | 01 |
| 07 | build | A faster route and incidents ahead, mid-trip | 01 |
| 08 | build | Open now and rating in "near me" ranking | 03 |
| 09 | build | Lanes on the banner, measured by missed turns | 01 |
| 10 | decision | A second POI source (Foursquare OS Places / Overture) on the engine | 01 |
| 11 | decision | Google Places: closed, on the terms | - |
| 12 | build | NAV_AUDIT in the engine's `device_logs` render | - |

**Order.** 01 runs first and is cheap: it is the "before" every later ticket is measured against.
02, 05 and 11 are ruled (2026-11-06); 10 is parked. 03 and 04 are the highest return for the least code.

## Decisions for Kevin

**All ruled 2026-11-06 (Kevin: *"ok for navigation. all of it"*), every recommendation accepted.**
Record: `memory/library/decisions.md`, 2026-11-06, "Navigation quality".

| # | Question | Ruling |
|---|---|---|
| D1 | Mapbox result names in the log (02) | The `mapbox_id` is logged on a `search` hit (2.7.6); never its name or address |
| D2 | Directions results in the log (02) | Distance and ETA kept, risk accepted in words; destination coordinates only for a saved place (first-party fix) |
| D3 | Billing shape (03) | `forward`, per request |
| D4 | How recents are kept (05) | The arrival GPS fix; build ticket [13](issues/13-recents-by-arrival-fix.md) |
| D5 | A second POI dataset (10) | Later; ticket 10 parked (KIV) |
| D6 | Google Places (11) | Closed |
| D7 | How long the engine keeps NAV_AUDIT | 90 days; a conversation batch's other lines still go at 60 |
| D8 | The upload at trip end | Kept |
| D9 | Contact addresses in `resolve` reasons | The label only, never the address text (calendar and suggestion addresses too) |
| - | TomTom (standing, mapbox-nav) | Retired: build-config key and credit removed; Kevin revokes the key at TomTom |

## Not yet specified

- Android Auto (`.scratch/android-auto/`).
- Offline search in dead zones (mapbox-plus 05 owns offline maps).
- Multi-stop optimisation ("errands in the best order").

## Out of scope

- Any hand-off to Google Maps or Waze (ADR 0054). ADR 0062's Matrix-home exception is unchanged.
- A key Kevin holds for anyone else (CLAUDE.md sec 7). Every key stays the household's.
- Comparative or anonymised fleet data (CLAUDE.md sec 7). NAV_AUDIT is one household's, on its own engine.
