---
map: generated-ui
ticket: "07"
title: "Harvest ui/generated/ before backend-erp phase 6 deletes it"
type: task
status: open
status-detail: "TIME-BOXED: must happen before backend-erp phase 6"
blockers: []
blocked-by: []
open-blockers: 0
ready: true
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

- [ ] Read all three files and write down what transfers: the field-type-to-editor mapping table,
      the validation layering, and how each handles a shape it does not recognise.
- [ ] Record it where the renderer's builder will actually find it - ticket 03 on this map, or a
      short doc under `docs/architecture/`. Not a comment in a file that is about to be deleted.
- [ ] Note explicitly what does NOT transfer, so nobody ports the data-schema coupling by habit.

## Verification

- [ ] The harvest doc exists and ticket 03 links to it.
- [ ] Done BEFORE backend-erp phase 6. If phase 6 arrives first, this ticket blocks it.
