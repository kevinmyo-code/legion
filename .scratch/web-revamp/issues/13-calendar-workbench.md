---
map: web-revamp
ticket: 13
title: Calendar workbench
type: build
status: open
status-detail: ""
blockers: ["09"]
blocked-by: ["[[09-the-event-sheet]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Calendar workbench

Charted 2026-10-03 with Kevin. Map: `.scratch/web-revamp/map.md`.

See `.scratch/web-revamp/spec.md` D10 (workbench).

## Build

- Week view (6:00 to 23:00, all-day lane) and Month view; Today / prev / next; click a slot to
  create, click an event to edit. Canvas status lines and done checkbox. Workbench Home's 7-day agenda.

## Verification

- [ ] vitest: overlapping events lay out side by side; an all-day event sits in the lane; a skip hides
      that occurrence; the done toggle PATCHes; private rows show "Only you".
- [ ] Shots at 1440x900 in `research/shots/13/`.
