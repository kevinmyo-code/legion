---
map: chief-of-staff
ticket: "06"
title: "Canvas sync on the server, so schoolwork is current enough to advise on"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Canvas sync on the server, so schoolwork is current enough to advise on

Charted 2026-09-12 from Kevin's own framing: *"alfred will be the executive of my estate, my chief of
staff."* See `.scratch/chief-of-staff/map.md` for what already exists - most of the advisor
machinery does - and for why this one is a gap rather than a rebuild.

## The state today

`canvas-integration` ticket 01 (built 2026-09-12) fixed `read_calendar` being unable to SEE
assignments. It did not touch the deeper problem: **nothing pulls Canvas at all.**

The only data is `research/planner-2026-09-01.json` - a snapshot pulled on 2026-09-01, and its own
`note` field says *"truncated at 50KB by get_page_text; 67 of an unknown larger total"*. Nobody knows
what is missing from it.

## Why this blocks the chief of staff specifically

An advisor cannot advise on coursework it cannot see, and **stale coursework is worse than none**: a
confident "nothing due" about a week Canvas knows about is exactly the failure this codebase keeps
rediscovering - the empty calendar, the empty `/api/changes` events list, both cheerful and both
wrong.

## Where it belongs

ADR 0044: Django is the engine and runs what must happen while the phone is asleep. `django-engine`
ticket 06's own brief already names Canvas polling as worker work. This is that, and it should be
built there rather than as a phone-side fetch.

It also carries the half `one-today` 08 asked for and nobody built: the snapshot captures `submitted`
per item, which is what would let a finished assignment tick itself.
