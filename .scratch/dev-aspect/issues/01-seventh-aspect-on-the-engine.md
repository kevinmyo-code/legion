---
map: dev-aspect
ticket: "01"
title: "The seventh aspect, and whether it rides the engine"
type: grilling
status: resolved
status-detail: "Resolved 2026-09-01 with Kevin. NOT an aspect - a read-only tool surface called projects. No engine record type, no widget page, no Room tables."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# The seventh aspect, and whether it rides the engine

## Resolution (Kevin, 2026-09-01)

**There is no seventh aspect. The aspect list stays at six.** This is a read-only **tool surface**
called **projects**, in the same class as location and weather - things the assistant can look up
and can never author.

### Why the charting decision was reversed

The map was charted on the assumption that projects would be aspect seven, and Kevin agreed to that
before the grill. The grill's question A killed it:

> An aspect on the engine is a thing you author and CRUD. Every project row here is a read-only
> mirror. You never create a project by voice, you never edit one, and if you did the next sync
> would blow the edit away.

Kevin's answers closed it: **no widget page**, and **only LEGION matters on the GitHub side for
now**. Strip the widget and strip the authoring and nothing aspect-shaped is left. Fleet survives
the same test only because vehicles, service records and builds are genuinely authored beside the
read-only OBD stream; projects has no equivalent.

### What this rules out, on purpose

- No engine record type, no `aspects`/`record_types`/`field_defs` rows.
- No Room tables, no migration, no `CarDatabase` version bump.
- No widget, no pager page.
- No CRUD voice tools. Nothing here is created, edited or deleted by the user.
- CLAUDE.md section 1's aspect list is **unchanged** - still six. No edit needed, and that is the
  point of the ruling.

### The name

**projects.** Kevin, 2026-09-01: *"projects."* It reaches voice copy - "what projects do I have",
"what is pending on projects" - and is frozen by this ruling. Not "dev", not "work", not "repos".

## Verification

- Entry in `memory/library/decisions.md` dated 2026-09-01, recording the reversal and its reason.
- CLAUDE.md section 1 is NOT edited. A ruling that changes nothing in the rules file is still a
  ruling; it is recorded in `decisions.md`, which is where "what happened, and when" lives.
- No migration lands under `supabase/migrations/` or `data/local/Migrations.kt` for this map.
