---
map: web-surface
ticket: "05"
title: "Look-back"
type: build
status: open
status-detail: ""
blockers: [" past events and tick history, the half of one-today 09 the web never got"]
blocked-by: ["09"]
open-blockers: 0
ready: true
tags: [ticket]
---

# Look-back

Charted 2026-09-12. See `.scratch/web-surface/map.md` for the evidence this rests on - every figure
there was read from the live engine in Kevin's own browser, not assumed.

## Why this exists

**Kevin, 2026-09-04:** *"end of day it records and resets. i can look back and see what i did."*
That is the half of `one-today` ticket 09 the phone got and the web never did.

There are **92 past events** and a per-day tick history in `checklist_ticks`, and nothing on the web
reaches either. The data is already there; this is a surface, not a feature.

## Build

- Past days reachable from Today - at minimum yesterday, ideally any date.
- A day in the past renders what was on it and what was ticked, read-only.
- Checklist history: for a DAILY list, which days it was ticked. **No score, no streak, no
  percentage** - CLAUDE.md §7's compulsion ban applies to a screen exactly as it does to a spoken
  raise, and a look-back is precisely where a streak would try to grow.

## Verification

A past day with real ticks renders them; a past day with none says so in words and does not imply
failure.
