---
map: nav-quality
ticket: "14"
title: "Build: forget recent places by voice, and decide their engine copy"
type: build
status: open
blockers: ["13"]
blocked-by: ["[[13-recents-by-arrival-fix]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Build: forget recent places by voice, and decide their engine copy

Left from [ticket 13](13-recents-by-arrival-fix.md), built 2026-11-06 with a phone-only store.

## Forget by voice

A voice tool ("forget my recent places") calling `RecentPlaces.forget()`, the same controller the Data
and privacy row calls (ADR 0035). Adding it regenerates `docs/voice.html` and needs copy in
`tools/voice_guide_copy.py`; build it after `fix/coach-voice` lands on `dev`, which edits both.

## The engine copy

Ticket 05 said "Room plus the engine, private to the member who drove (ADR 0052 and ADR 0075's
pattern)". Today the rows live in the phone's private prefs only. Whether they move to the engine
(survive a reinstall, reach the web) is a small ruling for Kevin before any code: a private,
member-owned table, forgettable as a hard delete.

## Verification

- Suite green on the tool's success and failure sentences.
- On the phone: "forget my recent places" empties the Data and privacy row.
