---
map: web-revamp
ticket: 17
title: "Fleet, places and notes workbench"
type: build
status: built
status-detail: "Built on feat/web-aspects: /fleet, /places, /notes, shots in research/shots/17. Needs Kevin on live data."
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

- [x] vitest per screen: smoke at both surfaces, one CRUD round-trip per editable table, the audio
      sentence, empty states in words. DONE; vehicles are edit-only and notes are read-only, see the
      resolution note below.
- [x] Shots in `research/shots/17/`.

## Resolution (2026-10-03, branch `feat/web-aspects`)

Built: `/fleet` (vehicle chips; vehicle edit; service history and maintenance schedules CRUD; drives read
only; `obd_samples` never requested), `/places` (CRUD), `/notes` (read only).

Not built, and why, for Kevin to rule on:

- **No "add vehicle".** A vehicle's PUT is keyed by `origin_guid`, which is nullable, and what a
  server-created vehicle's guid should be is an open decision (MEMORY). A vehicle with no key is shown
  with a sentence that it cannot be edited here.
- **No place rename and no radius.** The engine keys a place by its `label` and a reminder names it by
  that string, so a rename would orphan references; and the place has no radius column.
- **No voice-note delete or edit.** Deleting removes the text while the audio stays on the phone, which
  breaks the summary, transcript, audio chain of ADR 0041, and the web cannot reach the audio.

Trust: service entries say "Observed" or "Asserted, not checked"; a seeded interval says "estimate"
and "LEGION's guess"; a drive with no MAF reading says "No fuel reading", never zero, and no MPG is
derived; notes say "Audio is not available on the web." at the top of every note.
