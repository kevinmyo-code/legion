---
map: canvas-integration
title: "Canvas: what is due, when it is due in Kevin's own timezone, and whether it is done"
charted: 2026-09-11
charted-by: "Kevin + Opus"
effort: "`.scratch/canvas-integration/`"
tickets: 1
open: 1
status: open
tags: [map]
---

# Canvas integration

**This directory held one research file and no map until 2026-09-11.** It got one because Kevin asked
the assistant what was due Sunday and it told him nothing was - while seven assignments were due that
night.

**Kevin, 2026-09-11:** *"the AI doesnt see anything thats due on sunday for sch. i asked it and it
didnt know."*

## What is actually wrong, in one line

Canvas writes an 11:59pm local deadline as `04:59Z the next day`. **Every Sunday deadline is stored as
Monday in UTC**, and anything bucketing by the UTC date part empties Sunday. The assistant is not
failing to look; it is looking at a day something else emptied.

## Two problems, and they are independent

1. **The day is wrong** - ticket 01. Seven items due Sun 2026-09-13 23:59 local all carry
   `due_at_utc = 2026-09-14T04:59Z`. Whether the fault is in the import (flattened to an all-day
   Monday row) or the read (bucketing an instant by UTC date) is **not yet known**, and the two need
   different fixes. One row from the phone settles it.
2. **There is no sync** - not yet ticketed, deliberately. Nothing pulls Canvas. The only data is a
   snapshot pulled 2026-09-01, ten days stale, and self-described as *"truncated at 50KB... 67 of an
   unknown larger total"*. ADR 0044 puts this on the server - Django polling while the phone is
   asleep is django-engine's stated reason to exist.

**Fixing (1) on (2)'s ten-day-old data still leaves him asking about a week Canvas knows about and
LEGION does not.** But (1) is the one that makes the app actively misleading rather than merely
behind, so it goes first.

## Not reopened

`one-today` ticket 08 ("An event passes. A task gets done") owns the event/task split and the
`submitted`-to-auto-tick idea Kevin raised on 2026-09-01. It is `ready` and unstarted. This map
depends on it for (2) and does not restate it.

## The tickets

| # | Type | What | Blocked by |
|---|---|---|---|
| 01 | build | Seven things are due Sunday and the assistant says it knows of none | - |

Sync gets its ticket once 01 has established what the stored rows actually look like - charting an
ingestion path before knowing whether the existing rows are instants or flattened dates would be
guessing at the schema it has to write into.
