---
map: web-surface
ticket: "04"
title: "The desktop shell"
type: build
status: open
status-detail: ""
blockers: [" real width, a dashboard, and a rail that reaches the records"]
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# The desktop shell

Charted 2026-09-12. See `.scratch/web-surface/map.md` for the evidence this rests on - every figure
there was read from the live engine in Kevin's own browser, not assumed.

## What this builds

Ticket 03's ruling, made real. The current desktop is the phone's column in `max-w-lg` inside a
1707px window - roughly 70% empty, with a two-item rail.

- **Real width.** A layout that uses the screen, not a centred phone column.
- **The rail**, carrying whatever 03 decided it carries, and no item that opens an empty page.
- **A dashboard beside the day**, holding the few things 03 picked.
- **Ingestion**, wherever 03 put it.

## The constraint that outlives the layout

Whatever lands here has to have room for the §4 disclosures before the ledger screens arrive.
`docs/design/today.md` said it first and it still binds: do not set a visual tone that the aspect
screens then have to break out of to make room for `unverified` and `estimate` in words. Those words
are not furniture and they are not a footnote.

## Verification

At 1707px and at 1280px. And at 390px, where every desktop-only affordance must be absent rather
than merely hidden - Mia should not be able to reach an ingestion control by rotating her phone.
