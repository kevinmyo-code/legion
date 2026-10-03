---
map: web-revamp
ticket: 07
title: Shared and private on the web
type: build
status: built
status-detail: "Built and green: VisibilityMark on every event, task and list row, the list-header toggle (not optimistic, 403 sentence verbatim), redacted tombstones dropped. Owed: a run against the live engine with a real second member."
blockers: ["03", "06"]
blocked-by: ["[[03-the-shell-split-by-viewport]]", "[[06-private-rows-on-the-engine]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Shared and private on the web

Charted 2026-10-03 with Kevin. Map: `.scratch/web-revamp/map.md`.

See `.scratch/web-revamp/spec.md` D3, D10, D11.

## Build

- `src/components/visibility-mark.tsx`: shared = pink marker plus "Shared" (or a two-person glyph
  with `aria-label="Shared"`); private = lock plus "Only you". Used by event rows, list headers,
  calendar chips. Never colour alone.
- Privacy toggle on list headers (the event sheet's toggle is ticket "The event sheet").
- Redacted tombstones drop rows (already true for `deleted_at`; add the test).

## Verification

- [x] vitest: marker text present for both states; toggle PATCHes `visibility`; the 403 sentence
      shows verbatim; a redacted tombstone removes the row.
