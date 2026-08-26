---
map: hardening
ticket: "07"
title: "Attribution owed on two data sources LEGION already ships"
type: task
status: resolved
status-detail: "Credits shipped on the Data and privacy screen; the lint remains optional"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Attribution owed on two data sources LEGION already ships

## Question

Found 2026-08-26 while reading `DATA_SOURCES.md` in `bilawalsidhu/gods-eye-view`, a project that
happens to use several of the same feeds and documents their terms carefully. Two of the feeds
LEGION already ships carry attribution obligations that are **licence terms, not etiquette**, and
neither is currently met.

The repo is public, so this is visible.

### 1. Open-Meteo is CC BY 4.0 and requires a LINKED credit (`traced`)

`weather/WeatherController.kt:47` calls `api.open-meteo.com/v1/forecast`, and the result is rendered
on `ui/TodayScreen.kt` via `weatherLine`. Open-Meteo's licence is **CC BY 4.0 with a link
required** - the canonical form is "Weather data by Open-Meteo.com" linking to the site.

Every mention of Open-Meteo in this codebase today is a **code comment**
(`WeatherController.kt:15`, `:57`, `ui/TodayGapResolvers.kt:223`, `ui/TodayScreen.kt:178`). Nothing
user-facing credits it. The file's own doc comment even celebrates it as "free and keyless, so no
API key or billing", which is exactly the framing that makes it easy to forget it still has terms.

### 2. TomTom requires "Traffic flow data © TomTom" (`traced`)

`TOMTOM_API_KEY` is a real `buildConfigField` (`app/build.gradle.kts:83`) and the traffic feed is
live. A grep for `tomtom` across `ui/` returns **nothing**, so the credit appears nowhere the user
can see it.

## RESOLVED 2026-08-26

Credits ship on the **Data and privacy** screen, which already answers "where does what you see come
from, and what is kept". A dedicated route was considered and rejected for tonight: it needs
`LegionRoute` plus NavHost wiring, and that is more surface than a credit list warrants when it
could not be eyeballed on the phone before landing.

Shipped: "Weather data by Open-Meteo.com, used under CC BY 4.0", "Traffic flow data (c) TomTom", and
a courtesy line covering the US public-domain feeds (USGS, NWS, FEMA, NIFC).

**The lint is NOT done and stays open as the interesting half.** A list nobody is forced to update
goes stale the first time someone adds a feed in a hurry, which is exactly how these two came to be
missing. `voice_guide.py` failing on a tool with no copy is the pattern to copy.

## Fix

- [x] One attribution surface, reachable from settings. A short list, each credit a real link:
      "Weather data by Open-Meteo.com", "Traffic flow data © TomTom", plus anything else with terms
      (check USGS, NWS, FEMA, NIFC and AirNow from the location-intelligence map - most are US
      public domain and ask only for courtesy credit, but confirm rather than assume).
- [ ] Mission-control components, CLAUDE.md §9 wording (no em dashes, no emojis).
- [ ] A test or a lint that fails if a new networked data source lands with no credit, in the same
      spirit as `voice_guide.py` failing on a tool with no copy. **Optional but the point**: an
      attribution list nobody is forced to update is a list that goes stale the first time someone
      adds a feed in a hurry.

## Why this is worth doing rather than shrugging at

It is a licence condition on code in a public repo, it costs an afternoon, and it is the kind of
thing that is embarrassing to be told about rather than to have handled. The clone-and-run promise
also implies a stranger can run this without inheriting a compliance problem.

## Not in scope

Adding new data sources. The same reading turned up NASA FIRMS, GDELT and Google News RSS as
plausible future feeds; none is filed here, and any of them would carry its own terms.
