---
map: location-intelligence
ticket: 6
title: "The departure advisor"
type: build
status: open
status-detail: ""
blockers: ["02"]
blocked-by: ["[[02-area-info-tool]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# The departure advisor

## What to build

"Leave now" - chain: a calendar event that HAS a location, geocode it, ETA with live traffic from
TomTom, compare against event time minus the prep buffer, raise.

## The bounded exception this carries

Settled decision 8, and it amends decision 4 (TomTom on request only), so its bounds are exact:

- **Only for a calendar event that has a location.**
- **Only inside a window before it** - roughly the last 90 minutes.
- **Stops the moment the event starts, or the advisor has spoken.**
- **Nothing else polls TomTom, ever.**

The prep buffer is **one global setting** Kevin can change (decision 15). Wrong for a flight versus a
dentist; he overrides by ignoring it. A learned buffer needs departure history that does not exist,
and a wrong learned buffer is invisible until he is late.

## Still to decide while building

- **How a raise is cancelled once he is already moving.** Genuinely open, and small.
- Which category it raises under. It is not Safety and it is not Digest - **Timing** is the honest
  fit, which means it IS subject to the daily cap.

## Rules

- Key from `KeyVault`, BYO. `BuildConfig.TOMTOM_API_KEY` is a dev convenience only - a build without
  a key must say "I can't check traffic without a TomTom key", never fail silently.
- **No ETA persisted to Room or Drive** until TomTom's caching clause has actually been read
  ([ticket 07](07-tomtom-caching.md) blocks any storage, not the feature).
- Spoken as "per TomTom".

## Verification

- Suite green on the window logic and the buffer arithmetic.
- **On the phone:** an event with a location producing a raise at the right time, and no TomTom call
  outside the window.
