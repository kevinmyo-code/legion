---
map: location-intelligence
ticket: 3
title: "Which AirNow endpoint survives the fall-2026 retirement"
type: research
status: resolved
status-detail: "2026-08-21 - endpoint identified; one logged-in visit still owed"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Which AirNow endpoint survives the fall-2026 retirement

## Question

`.scratch/hands-and-senses/research/14-location-intel.md` found AirNow's web-services index carrying
a heading, verbatim, **"Web Services that will be retired in the fall of 2026"** - with lat/lon
variants listed under it, and a differently-named lat/lon service on the surviving list. Ambiguous
from the index alone, and **fall 2026 is weeks away.**

Writing a client against an endpoint that dies in weeks is throwaway work, so this blocks the `air`
category of [ticket 02](02-area-info-tool.md) and nothing else.

Establish, from AirNow's own pages behind the login:

1. Which lat/lon observation endpoint is **not** retiring, with its exact URL and parameters.
2. The response shape, including the AQI category vocabulary.
3. The published rate limits for a free key (ticket 14 could not see these - they are behind the
   login wall).
4. Confirm the mandatory disclaimer wording: data "are not fully verified or validated and should be
   considered preliminary and subject to change." **That has to be sayable in one short spoken
   clause** - work out what it becomes out loud.

Requires a free AirNow key (no card). If signup is needed, that is a task for Kevin - say so rather
than stalling.

## Answer - 2026-08-21. Safe to build, against a DIFFERENT endpoint than the one we had.

The whole answer sits in a PDF the index page does not link:
**`https://docs.airnowapi.org/docs/AirNowAPIUpdates2026June.pdf`**, fetched live.

### The two services, and they are genuinely different

| | Retiring | **Use this** |
|---|---|---|
| Path | `/aq/observation/latLong/current/` | **`/aq/observation/current/ziplatlong/`** |
| Index section | under *"Web Services that will be retired in the fall of 2026"* | top section, no banner |
| Status | **Retirement September 30, 2026** | live since 2026-06-17 |

The `current` segment **moves from the tail to the middle**, and the locator becomes a trailing
`ziplatlong` segment - `/observation/{locator}/current/` becomes `/observation/current/{locator}/`.
Easy to mistype into the retiring one. The new service folds zip code and lat/lon onto one endpoint.

**Probed live** (no key, so a 401 means "this route exists" and a 302-to-login means it does not):

```
/aq/observation/current/ziplatlong/  -> 401 Request not authenticated   <- exists
/aq/observation/latLong/current/     -> 401                              <- exists, retiring
/aq/observation/current/             -> 302                              <- no such route
```

### The retirement date is exact, and the index page hides it

The index says only *"fall of 2026"*. The PDF says **September 30th, 2026**, and AirNow states no
grace period, no deprecation header, and no post-cutoff error contract - just "update endpoint URLs
before the retirement date". **Verdict: build now.** The replacement is already live, and waiting
only shortens the migration window.

### Three things that are NOT established, and one visit fixes all of them

`[not-established]`, because the per-service doc pages are behind a login:

1. **Exact parameter names** for the new endpoint. Inferred-not-verified: `latitude`, `longitude`,
   `format`, `API_KEY`, optional `distance`.
2. **Response field names.** Deliberately not guessed.
3. **The hourly rate limit.** The policy IS public and is unusual enough to design around: it is
   **per key, per hour, per service**, and exhausting it **stops returning data until the next clock
   hour** rather than erroring in a way you would notice. The number is behind the wall.

**Kevin owes one visit** ([ticket 10](10-airnow-account.md)): request an account, activate it, log
in, open `docs.airnowapi.org/ObservationsByZipCodeLatLon/docs`. That one page yields all three, plus
the key itself.

### What IS public, and constrains the design

**The AQI vocabulary** - use these names and colours, do not invent:

| AQI | Category | Colour |
|---|---|---|
| 0-50 | Good | Green |
| 51-100 | Moderate | Yellow |
| 101-150 | Unhealthy for Sensitive Groups | Orange |
| 151-200 | Unhealthy | Red |
| 201-300 | Very Unhealthy | Purple |
| 301-500 | Hazardous | Maroon |

**Real-time values are NowCast, not raw readings.** Worth knowing before anyone "explains" a number.

**Data is hourly, published 10-30 minutes past the hour.** A 15-minute hazard cadence
([decision 12](../map.md)) will therefore re-fetch unchanged data three times an hour. **Air quality
should be fetched hourly, not on the hazard tick** - and it is pull-only anyway (decision 5), so this
costs nothing to honour.

### Three obligations that bind the client, all verbatim from the Data Use Guidelines

1. **The preliminary disclaimer must be surfaced**, not merely known: *"If observational data are
   used for analyses, displayed on web pages, or used for other programs or products, the analysis
   results, displays, or products must indicate that these data are preliminary."*
2. **Credit the agencies and the AirNow program**, first: *"Credit should first be given to the
   appropriate source - federal, state, local, and tribal air quality agencies and the EPA AirNow
   program."*
3. **The data may not be altered:** *"Air quality data, forecast values, and advisory statements
   should not be altered in any way and should be disseminated as received."*

**Point 3 has teeth for a voice assistant, and it lands on the right side of an existing rule.** The
AQI number and its category name are passed through verbatim - the model may not round, rephrase, or
"interpret" them. That is [decision 12](../map.md)'s pre-formatted-with-attribution rule anyway, so
AirNow's terms and this map's own posture agree. It also means **the news-summarising split from the
sitrep must never be pointed at AQI**: prose gets summarised, measurements do not.

**Point 1 needs a spoken form.** The full paragraph is unspeakable. Something like *"AirNow says
this is preliminary data"* - one clause, appended to the AQI reading, decided when
[ticket 02](02-area-info-tool.md) writes the formatter.

Note also that the Guidelines end in a signature form returned to `dmc@airnowtech.org`, so formally
this is an agreement rather than a notice.
