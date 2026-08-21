---
map: location-intelligence
ticket: 10
title: "Get the AirNow key, and the three facts behind its login"
type: task
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Get the AirNow key, and the three facts behind its login

## Why this is a ticket rather than a line in another one

[Ticket 03](03-airnow-endpoint.md) established the endpoint but hit a login wall on three things a
client cannot be written without. This is manual work only Kevin can do, and it blocks the `air`
category of [ticket 02](02-area-info-tool.md) - nothing else on the map waits on it.

**Free. No credit card.**

## Checklist

1. Request an account: `https://docs.airnowapi.org/account/request/`
2. Activate it from the emailed confirmation code.
3. Log in: `https://docs.airnowapi.org/login`
4. The **API key** is shown top-right on the Web Services page. Paste it here or into
   `local.properties` as `AIRNOW_API_KEY` - **never into a tracked file**; this repo is public.
5. Open `https://docs.airnowapi.org/ObservationsByZipCodeLatLon/docs` and capture three things:
   - the **exact parameter names** (we infer `latitude`, `longitude`, `format`, `API_KEY`, optional
     `distance` - unverified),
   - the **response field names**,
   - the **hourly rate limit** for this service.

## Why point 5 matters more than it looks

AirNow's rate limiting is **per key, per hour, per service**, and exhausting it **stops returning
data until the next clock hour** - it does not fail in a way anyone would notice as a rate limit.
A client written without knowing the number can silently produce an app that goes quiet for 40
minutes an hour and looks broken for no visible reason.
