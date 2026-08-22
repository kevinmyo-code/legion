---
map: drive-ui
ticket: 12
title: "What the driving screen is FOR, now that gauges are out"
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# What the driving screen is FOR, now that gauges are out

## The re-scope

Kevin, 2026-08-22: *"drive ui needs a new pass. we dont need rpm and sped live, since car gauges
does that. its more of status monitoring. sensors etc."*

**That deletes the premise the screen was built on.** Speed and rpm were the centre of it, and the
car renders both, six inches away, better, and without a phone. A phone repeating them is a worse
copy of an instrument that already exists. Every layout ticket that argued about where the gauges go
was answering the wrong question.

The screen keeps existing because a phone knows things the dashboard does not: what the OBD adapter
can read but no dash light shows until it is too late, what has changed since last drive, and what
is worth saying out loud.

## Decide

1. **What is "status monitoring" concretely?** Coolant and intake temps, fuel trims, battery
   voltage, pending trouble codes, load - the values that drift before anything on the dashboard
   lights up. Which ones actually earn a place, and which are just available?
2. **Glance or watch?** A monitoring screen you stare at is a worse gauge cluster. One you glance at
   needs to be near-empty until something matters. **The second shape argues for almost nothing on
   screen most of the time**, which is a very different design from what exists.
3. **What deserves to interrupt?** A value crossing a threshold could colour a tile, or it could be
   spoken. Speaking is the app's actual strength and the screen may be the wrong surface entirely.
4. **What is the fallback when there is no OBD adapter connected?** Most of this has no data at all
   without the dongle, and an empty monitoring screen must read as "not connected", never as "all
   fine". A screen that looks calm when it is blind is the worst version of this feature.
5. **Does the trip content survive?** `LAST DRIVE` elapsed and distance are already built and are
   not gauges. Keep, or does a status screen want the current drive instead?
6. **Does this screen still deserve to be a whole mode?** It currently takes over the app with its
   own chrome. If it is mostly empty and mostly ambient, a card on the home screen might be the
   honest size of it.

## Out of scope

Do not re-open EXIT's confirm, the Alfred strip's position, or portrait-only - those were settled
and built, and none of them depended on the gauges.
