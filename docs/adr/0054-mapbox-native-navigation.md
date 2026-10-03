---
status: accepted
decided: 2026-10-03
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-10-03 - Mapbox returns: full native navigation, Mapbox only, for maximum voice control]]"
tags: [adr]
---

# 54. Navigation is native Mapbox, and Mapbox only

## Standing

**LEGION navigates in-app with the Mapbox Navigation SDK, on the household's own Mapbox token.
There is no hand-off to another map app.** With no token, or with the SDK unable to run, navigation
is unavailable and the assistant says so in words and points at Setup. Phone only.

Until the Mapbox path ships, `open_navigation`'s Google Maps hand-off stays live. It is removed in
the same change that ships its replacement, never ahead of it.

## Context

The Google Maps hand-off works (verified on the phone 2026-08-19) and is a dead end for voice: once
Maps is open, nothing public lets LEGION reroute, add a stop, cancel, or read the next turn or ETA.
Kevin wants the assistant to control a trip, not just start one. That requires LEGION to own the
route. Mapbox's free tier covers a two-user household many times over. Cost was the original
reason Mapbox was killed, and it stopped being a reason once the token became BYO.

## Consequences

- Mapbox leaves CLAUDE.md §3's dropped list.
- Every navigation voice tool reports from the SDK's own state: a route that was not set, a stop
  that was not added, is said as not done (§7 outcome verbs).
- Every navigation voice tool has a hands path on the nav screen (ADR 0035).
- A trip session runs only while the user is navigating. Free-drive in the background would bill
  trips and drain the battery.
- Clone-and-run: a stranger's clone must still build and run with no Mapbox credentials of any kind.
  Navigation is then absent, said in words. How the build achieves that is
  `.scratch/mapbox-nav/` ticket 02.
- Android Auto is not covered here.
