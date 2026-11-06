---
map: nav-quality
title: "Search and routing close to Google Maps, measured"
charted: 2026-11-06
charted-by: "Kevin + Opus"
effort: "`.scratch/nav-quality/`"
tickets: 12
open: 12
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
| 06 | build | Route to the entrance, and measure wrong picks | 01 |
| 07 | build | A faster route and incidents ahead, mid-trip | 01 |
| 08 | build | Open now and rating in "near me" ranking | 03 |
| 09 | build | Lanes on the banner, measured by missed turns | 01 |
| 10 | decision | A second POI source (Foursquare OS Places / Overture) on the engine | 01 |
| 11 | decision | Google Places: closed, on the terms | - |
| 12 | build | NAV_AUDIT in the engine's `device_logs` render | - |

**Order.** 01 runs first and is cheap: it is the "before" every later ticket is measured against.
02 and 11 are rulings that can happen today. 03 and 04 are the highest return for the least code.

## Decisions for Kevin

- **D1 (ticket 02): Mapbox result names in the log.** Today: never (2.7.2). Option: log the
  `mapbox_id`, which 2.7.6 lets LEGION store, so a pick can be looked up again.
- **D2 (ticket 02): Directions results in the log.** `route_request` keeps distance, ETA and
  coordinates rounded to about 110 m. A strict reading of 2.10.1 ("shall not ... store results")
  forbids it. Built as asked; Kevin accepts the risk or it is cut back.
- **D3 (ticket 03): billing shape.** `forward` bills per request (25,000 free a month) instead of per
  session (2,500 free). Cheaper; the same token.
- **D4 (ticket 05): how recents are kept.** The arrival fix (first-party, like `tag_place`) or the
  `mapbox_id` (2.7.6).
- **D5 (ticket 10): a second POI dataset on the engine.** No API key at query time; a Foursquare
  Places Portal token only to download. Engine disk and a refresh job.
- **D6 (ticket 11): Google Places.** Recommended closed: 14.2, plus a new billed Google key, plus
  ADR 0054.
- **D7: how long the engine keeps NAV_AUDIT batches.** Same as conversation batches unless ruled.
- **D8: the upload at trip end.** `trip_end` calls `ConversationLog.uploadSoon()`, so a hand-started
  trip's lines reach the engine without waiting for the next voice session. The other choice is to
  let them ride along with the next upload.
- **D9: contact addresses in `resolve` reasons.** A contact whose address cannot be geocoded logs a
  not-found sentence that quotes the address text. That is the household's own contact data, but it
  is a third party's address in a log. Keep it, or log the label only.
- **Standing, from mapbox-nav: TomTom.** `TOMTOM_API_KEY` is still baked and almost unused. Retire it.

## Not yet specified

- Android Auto (`.scratch/android-auto/`).
- Offline search in dead zones (mapbox-plus 05 owns offline maps).
- Multi-stop optimisation ("errands in the best order").

## Out of scope

- Any hand-off to Google Maps or Waze (ADR 0054). ADR 0062's Matrix-home exception is unchanged.
- A key Kevin holds for anyone else (CLAUDE.md sec 7). Every key stays the household's.
- Comparative or anonymised fleet data (CLAUDE.md sec 7). NAV_AUDIT is one household's, on its own engine.
