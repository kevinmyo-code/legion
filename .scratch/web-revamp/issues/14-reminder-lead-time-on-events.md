---
map: web-revamp
ticket: 14
title: Reminder lead time on events
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Reminder lead time on events

Charted 2026-10-03 with Kevin. Map: `.scratch/web-revamp/map.md`.

See `.scratch/web-revamp/spec.md` D7 (the column).

## Build (server)

- `events.remind_minutes_before` int null, CHECK in (0, 5, 10, 15, 30, 60, 120, 1440). Serializer
  read/write, 400 sentence naming the allowed set. OpenAPI + `gen:api`.

## Verification

- [ ] pytest: accepted values round-trip; others 400; null clears. Phone untouched (it ignores
      unknown keys: `DjangoEventsBackend.kt:19`).
