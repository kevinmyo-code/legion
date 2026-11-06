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
4. Run `python tools/nav_audit_report.py nav.txt --json > .scratch/nav-quality/research/baseline-2026-11.json`.
   That file holds counts and query text Kevin said, and no Mapbox result content.

## Record

- Search: success rate, ambiguous rate, zero-result phrases, picked-rank distribution, `search`
  source p50/p95.
- Routes: p50/p95, failure kinds, share with traffic.
- Trips: reroutes per guided hour, off-route reroutes, ETA error mean and abs p95, cue drops.

## Done when

The JSON is committed and the map's ranked table has a "before" column filled from it.
Fewer than 20 searches or 5 arrivals is too thin: extend the window.
