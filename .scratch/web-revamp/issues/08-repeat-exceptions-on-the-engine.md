---
map: web-revamp
ticket: 08
title: Repeat exceptions on the engine
type: build
status: open
status-detail: "Server half built and green (skip routes, event_skips in the feed, api/recurrence.py, 26 phone-drawn vectors). Web half owed: lib/recurrence.ts reading the same vectors."
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Repeat exceptions on the engine

Charted 2026-10-03 with Kevin. Map: `.scratch/web-revamp/map.md`.

See `.scratch/web-revamp/spec.md` D4.

## Build (server + shared vectors)

- `event_skips.updated_at`, `deleted_at` (additive). Skip routes under `/api/events/<id>/skips`.
  `event_skips` in `/api/changes?aspects=events`.
- `server/api/recurrence.py` expansion; `server/tests/fixtures/recurrence_vectors.json` drawn first
  from the phone's own expansion tests (name the Kotlin test in each vector).
- Web: move expansion from `lib/day.ts` / `lib/horizon.ts` into `src/lib/recurrence.ts`, read the
  same JSON in `recurrence.test.ts`, and honour skips everywhere occurrences render.

## Verification

- [x] pytest: skip routes idempotent, scoped through `visible()`, travel the feed, tombstone on DELETE.
- [ ] pytest and vitest both pass every vector; a deliberately broken vector fails both.
