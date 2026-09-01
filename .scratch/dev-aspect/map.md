---
map: dev-aspect
title: "Map: The projects tool surface - what is pending, by voice"
charted: 2026-09-01
charted-by: "Kevin + Opus"
effort: "`.scratch/dev-aspect/`"
tickets: 8
open: 4
status: open
tags: [map]
---
# Map: The projects tool surface - what is pending, by voice

## Destination

Kevin asks LEGION what projects he has and what is pending on each, and gets an answer built from
sources he could verify himself. Not from a prose summary somebody wrote weeks ago.

Kevin, 2026-09-01: *"i wanna be able to query my current projects in my repos etc ... i want to ask
the voice ai what projects i have, whats pending on which project."*

## Notes

**Domain:** LEGION, Android phone app, `com.kevin.legion`. Read `CLAUDE.md` for rules,
`memory/MEMORY.md` for state.

CLAUDE.md sections 1 (the six aspects; unreadable vs empty), 4 rule 5 (anything the source does not
state is an estimate), 7 (third-party content is read-through only; no Kevin-hosted anything; every
voice capability has a hands path) bind everything here.

## The shape, after the 2026-09-01 grill

Charting assumed a seventh aspect backed by Supabase tables and two cron syncs. The grill and
Kevin's scope answers collapsed it into something much smaller, and **both charting assumptions
were reversed**:

| Charted as | Ruled |
|---|---|
| Aspect seven, on the engine, with a widget | **Not an aspect.** A read-only tool surface named `projects`. Aspect list stays at six (ticket 01) |
| GitHub sync into Supabase, every repo, on a cron | **A static feed.** `docs/board.json` on GitHub Pages, generated from ticket frontmatter (ticket 05) |
| Azure DevOps sync into Supabase | **Read-through at voice time.** Nothing persisted, ever (ticket 02) |
| A per-project prose summary | **Killed.** No such field (ticket 04) |

**Net: no new table anywhere, no Edge Function, no cron, no Room migration, no backend.** What is
left is one static JSON file this repo already had the data for, and one on-device HTTP client.

### What drove each reversal

1. **Not an aspect.** An aspect is a thing you author and CRUD; every row here is a read-only
   mirror. Kevin wanted no widget and only cares about LEGION on the GitHub side, and stripping the
   widget and the authoring leaves nothing aspect-shaped. Fleet survives the same test only because
   vehicles and service records are genuinely authored beside its read-only OBD stream.
2. **A static feed.** LEGION has no GitHub issues - its pending work is ticket frontmatter that
   `tools/pending_wiki.py` already parses, the commit hook already regenerates, and Pages already
   publishes. Emitting `board.json` beside `index.html` costs one function and removes the entire
   sync: no API, no PAT, no rate limits, and no delete-discipline problem, because the file is
   rebuilt whole every time so a resolved ticket cannot linger.
3. **Read-through.** Kevin: *"its company's azure, but its all my own projects, solo dev work."*
   That weakens section 7's third-party concern - it is his own writing - but not the company's
   ownership of the tenant. Querying live and discarding satisfies section 7 natively instead of
   carving an exception out of it, and costs a second of latency on a question asked occasionally.

### Locked (Kevin, 2026-09-01)

1. The surface is named **projects**. It reaches voice copy and is frozen.
2. **No widget, no pager page.**
3. GitHub scope is **LEGION only** for now.
4. **What is pending comes only from falsifiable sources** - ticket frontmatter and work items.
5. **Nothing from Azure DevOps is ever written down.**

### The one thing Kevin still owns

Company policy on connecting a personal device to their Azure DevOps with a PAT. Read-through
removes the data-at-rest question; it does not remove the access question. Ticket 03's research
also found that an Entra-backed org **can disable PAT creation outright**, which would make ticket
06 unbuildable - worth checking before building it. And the org Usage page shows an admin the IP,
User-Agent and URI of every REST call, so this is visible even though work-item reads are not
audited.

## Tickets

| # | Type | Status | Title |
|---|---|---|---|
| 01 | grilling | resolved | The seventh aspect - ruled NOT an aspect |
| 02 | grilling | resolved | Azure boundary - ruled read-through, never persisted |
| 03 | research | resolved | The Azure DevOps REST API - `fields` allowlist confirmed |
| 04 | grilling | resolved | The prose summary - ruled it does not exist |
| 05 | build | open | The LEGION board feed (`docs/board.json`) |
| 06 | build | open | The Azure DevOps read-through client |
| 07 | build | open | The staleness contract, and the three states of not-knowing |
| 08 | build | open | The projects tool surface, and its hands path |
