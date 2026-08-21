---
map: location-intelligence
ticket: 3
title: "Which AirNow endpoint survives the fall-2026 retirement"
type: research
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
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
