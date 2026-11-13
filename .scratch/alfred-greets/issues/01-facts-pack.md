---
map: alfred-greets
ticket: "01"
title: "The facts pack: what Alfred may mention, from real data"
type: build
status: built
status-detail: "built 2026-11-13: engine (35a55c77) and phone (greeting/: pack fetch, place source, the facts block that names the only content a greeting may cite). Owed: engine deploy, then a run away from home for the place source"
blocked-by: []
tags: [ticket]
---

# The facts pack

One structured pack the greeting and every check-in are built from (ADR 0091, "facts first"):
events that ended in the last 24 hours, today's remaining events, open todos and Canvas work due
within a day, today's workout and weigh-in, budget pace, and the current place when away from home
(city or area name from the phone's fix, never an IANA zone id). Each source says one of: facts,
empty, or unreadable (and why). The engine composes what it holds (it already composes the brief:
`server/brief/compose.py`); the phone adds place. A topic already answered or silenced is left out.

## Verification

Tests: each source's three states; an unreadable source is never rendered as empty; the pack is
the only content the greeting prompt may cite (the prompt says so in words).
