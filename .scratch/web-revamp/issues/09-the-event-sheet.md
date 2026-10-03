---
map: web-revamp
ticket: 09
title: "The event sheet: add, edit, repeats, this one or all"
type: build
status: open
status-detail: ""
blockers: ["03", "07", "08", "14"]
blocked-by: ["[[03-the-shell-split-by-viewport]]", "[[07-shared-and-private-on-the-web]]", "[[08-repeat-exceptions-on-the-engine]]", "[[14-reminder-lead-time-on-events]]"]
open-blockers: 4
ready: false
tags: [ticket]
---
# The event sheet: add, edit, repeats, this one or all

Charted 2026-10-03 with Kevin. Map: `.scratch/web-revamp/map.md`.

See `.scratch/web-revamp/spec.md` D8, D4.

## Build

- `src/components/event-sheet.tsx` (bottom sheet at family width, side panel at workbench width).
- `api/mutations.ts`: `useCreateEvent`, `useUpdateEvent`, `useDeleteEvent`, `useSkipOccurrence`,
  `useEditOccurrence` (skip + one-off with `origin_guid "<series>:<date>"`).
- "+" entry points on family Home and both Calendar variants.

## Verification

- [ ] vitest: create one-off; create weekly with chips and end-after-N; edit "just this one" sends
      the skip then the POST with the derived origin_guid, and a retry does not duplicate; delete
      "just this one" sends only the skip; "all of them" PATCHes/DELETEs the series; a server 400
      sentence shows and the sheet stays open; reminder and visibility round-trip.
- [ ] Shots in `research/shots/09/`.
