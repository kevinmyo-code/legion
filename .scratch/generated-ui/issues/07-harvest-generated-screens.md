---
map: generated-ui
ticket: "07"
title: "Harvest ui/generated/ before backend-erp phase 6 deletes it"
type: task
status: resolved
status-detail: "Harvested 2026-08-26 to docs/architecture/generated-ui-harvest-2026-08-26.md, before phase 6"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Harvest ui/generated/ before backend-erp phase 6 deletes it

## Question

**This is the one ticket on this map that is time-boxed against the backend arc, and the only one
that may run during it.**

`ui/generated/` is 596 lines that already render list, detail and form screens from a schema:

| File | Lines | What it solves |
|---|---|---|
| `ui/generated/GeneratedFormScreen.kt` | 293 | One editor per field type, two-layer validation |
| `ui/generated/GeneratedDetailScreen.kt` | 161 | Rendering an arbitrary record's fields |
| `ui/generated/GeneratedListScreen.kt` | 142 | Rendering an arbitrary record type as a list |

Its input is a DATA schema (`field_defs`) rather than a UI schema, so it is not the renderer this
map needs. But it is the closest prior art in the repo for the problems that renderer will hit:
mapping a type to a Compose editor, validating before committing, rendering an unknown shape
without crashing, and doing all of it in mission-control components.

**[[0039-per-aspect-typed-tables]] retires it**, and backend-erp phase 6 deletes it. Once deleted,
the next person rebuilds the same lessons from scratch.

## What to do

- [x] Read all three files and write down what transfers: the field-type-to-editor mapping table,
      the validation layering, and how each handles a shape it does not recognise.
- [x] Record it where the renderer's builder will actually find it - ticket 03 on this map, or a
      short doc under `docs/architecture/`. Not a comment in a file that is about to be deleted.
- [x] Note explicitly what does NOT transfer, so nobody ports the data-schema coupling by habit.

## Verification

- [x] The harvest doc exists and ticket 03 links to it.
- [x] Done BEFORE backend-erp phase 6 (backend-erp was at phase 1 on 2026-08-26).

## Resolution (2026-08-26)

Harvested to `docs/architecture/generated-ui-harvest-2026-08-26.md`; ticket 03 gained a **Prior
art** section linking it. Section 5 of the doc is the explicit do-not-port list.

**One thing worth knowing beyond the ticket's ask.** `tools/docs_check.py` resolves backticked
package-relative Kotlin paths in `docs/` and fails on any that do not exist. The three harvested
files are scheduled for deletion, so the harvest doc names them **un-backticked on purpose** and
says why at the top. Backticking them would have planted a `docs_check` failure timed to fire
exactly when phase 6 landed. `python tools/docs_check.py` green after the write.

Phase 6 is the last phase of backend-erp and that arc was at phase 1 on this date, so this was
done with margin rather than against the deadline.
