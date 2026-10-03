---
map: web-revamp
ticket: 10
title: "Family Home, Lists and Calendar"
type: build
status: open
status-detail: ""
blockers: ["03", "07", "09", "11"]
blocked-by: ["[[03-the-shell-split-by-viewport]]", "[[07-shared-and-private-on-the-web]]", "[[09-the-event-sheet]]", "[[11-spend-on-the-engine]]"]
open-blockers: 4
ready: false
tags: [ticket]
---
# Family Home, Lists and Calendar

Charted 2026-10-03 with Kevin. Map: `.scratch/web-revamp/map.md`.

See `.scratch/web-revamp/spec.md` D6, D10 (family), D11 (family).

## Build

- `src/routes/_authed.index.tsx` renders `FamilyHome` at family width (sections in D6 order).
- Pins: `src/lib/pins.ts` (`legion.pins.v1`, try/catch, fallback rule), pin button on list headers.
- Spend card from `GET /api/ledger/spend`, category sheet on tap.
- Family `/lists` restyle with the collapsed Ticked section; family `/calendar` month grid + agenda.

## Verification

- [ ] vitest: each section's empty, unreachable and stale sentences differ; overdue capped at 3 plus
      "and N more"; pins persist and fall back; the spend card shows "unverified" beside the amount
      when true and "Could not reach the engine" (never $0) on failure; ticked items move to Ticked.
- [ ] Shots at 390x844 light and dark in `research/shots/10/`.
- [ ] Owed on live (Kevin): Mia's spend card equals the phone's Money figure to the cent.
