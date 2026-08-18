---
title: LEGION
tags: [home]
---

# LEGION

One Android phone app, three aspects: fleet, ledger, pantry.

## Start here

| | |
|---|---|
| [[Board]] | What is ready to build right now, and what is waiting on what |
| [[Tickets.base\|Tickets]] | Every ticket, filterable |
| [[Maps.base\|Maps]] | Every wayfinder map and its progress |
| [[Library.base\|Library]] | The memory shelves, live and frozen |

## Rules and state

- `CLAUDE.md` holds the rules. `memory/MEMORY.md` holds the state.
- If they disagree: MEMORY.md wins for state, CLAUDE.md wins for rules.

## Regenerating

The tickets are the source of truth. After editing a ticket header, run:

```
python tools/obsidian_sync.py
```

That rewrites frontmatter, the per-map canvases, and [[Board]].
