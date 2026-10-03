---
map: web-revamp
ticket: 04
title: Live refresh while the page is visible
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Live refresh while the page is visible

Charted 2026-10-03 with Kevin. Map: `.scratch/web-revamp/map.md`.

See `.scratch/web-revamp/spec.md` D13.

## Build

- `api/queries.ts` `useChanges`: `refetchInterval: 30_000` gated on `document.visibilityState`,
  `refetchOnWindowFocus`, `refetchOnReconnect`.
- A shared `useVisibleInterval(ms)` helper for later ledger queries (5 min).
- `Freshness` keeps showing the last good data's age after a failed refetch.

## Verification

- [ ] vitest with fake timers: refetches at 30 s while visible, not while hidden, immediately on
      becoming visible; a failed refetch leaves rows on screen and the freshness line says so.
