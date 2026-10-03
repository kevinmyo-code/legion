---
map: web-revamp
ticket: 17
title: "Fleet, places and notes workbench"
type: build
status: open
status-detail: ""
blockers: ["03"]
blocked-by: ["[[03-the-shell-split-by-viewport]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Fleet, places and notes workbench

Charted 2026-10-03 with Kevin. Map: `.scratch/web-revamp/map.md`.

See `.scratch/web-revamp/spec.md` D1 rail; web-and-households 06 trust rules carried over verbatim.

## Build

- `/fleet`: vehicles, service history, maintenance schedules (CRUD where the API allows), recent drives
  read only. `obd_samples` not shown (a 20k-point series is ECharts territory, out of scope).
- `/places`: tagged places CRUD.
- `/notes`: voice notes with transcript and summary; "Audio is not available on the web." in words.

## Verification

- [ ] vitest per screen: smoke at both surfaces, one CRUD round-trip per editable table, the audio
      sentence, empty states in words.
- [ ] Shots in `research/shots/17/`.
