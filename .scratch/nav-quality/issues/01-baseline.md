---
map: nav-quality
ticket: "01"
title: "Test: a NAV_AUDIT baseline from two weeks on the A25"
type: test
status: open
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Test: a NAV_AUDIT baseline from two weeks on the A25

Every later ticket claims a number moved. This records the number before anything moves.

## Steps

1. Install a build from `feat/nav-audit` on the A25. In Assistant settings, turn on "Keep on this
   phone and send to the engine".
2. Drive normally for two weeks. Use voice and typing both. Do the mapbox-plus 02 A25 drive inside
   that window.
3. Pull the batches from the engine (`manage.py device_logs --reason conversation`, raw), or capture
   `adb logcat -v threadtime -s NAV_AUDIT > nav.txt`.
4. Run `python tools/nav_audit_report.py nav.txt --json` and keep the output OFF the repo (the
   scratchpad, or the engine). **This repo is public and Pages serves `.scratch/`**, and the JSON's
   `zero_result` list is Kevin's own spoken phrases, which can be a street address or a person's
   name. Only the numbers go into this ticket.

## Record

- Search: success rate, ambiguous rate, zero-result phrases, picked-rank distribution, `search`
  source p50/p95.
- Routes: p50/p95, failure kinds, share with traffic.
- Trips: reroutes per guided hour, off-route reroutes, ETA error mean and abs p95, cue drops.

## Done when

The numbers below are filled in (counts and percentiles only, no phrases), and the map's ranked
table has a "before" column taken from them. The zero-result phrases stay off the repo; later
tickets replay them from the uncommitted capture.
Fewer than 20 searches or 5 arrivals is too thin: extend the window.
