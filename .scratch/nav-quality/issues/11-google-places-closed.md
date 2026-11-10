---
map: nav-quality
ticket: "11"
title: "Decision: Google Places, closed on the terms"
type: decision
status: resolved
status-detail: "2026-11-06, Kevin: closed"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Decision: Google Places, closed on the terms

## Question

Google's real lead is its POI data. Can LEGION search Google Places and route on Mapbox?

## Facts (Google Maps Platform Service Specific Terms, last modified 2026-10-28)

- **14.2:** "Customer must not use Google Maps Content from the Places API in conjunction with a
  non-Google map."
- **15.1:** the Places UI Kit may be used "with or without any map, including a non-Google Map".
- **15.3(c):** with a third-party service, the kit must still respect "the restrictions against
  caching and exporting Google Maps Content". Taking a UI Kit place out to route it on Mapbox reads
  as exporting it (reasoned). The Android overview does not say whether the components hand the app
  coordinates or a place ID.
- Any of it needs a new Google Maps Platform key with billing, held by the household.
- ADR 0054: LEGION's navigation is Mapbox, and other map SDKs are not reopened.

## Recommendation

Closed. If Kevin wants Google's data, the honest form is ADR 0062's: open Google Maps itself. That is
a hand-off, which ADR 0054 rules out for navigation.

Resolve by Kevin's word; no build follows.

## Resolution (Kevin, 2026-11-06)

Closed, as recommended (decision D6). No build follows.
