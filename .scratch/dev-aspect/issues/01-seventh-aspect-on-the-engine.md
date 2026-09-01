---
map: dev-aspect
ticket: "01"
title: "The seventh aspect, and whether it rides the engine"
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# The seventh aspect, and whether it rides the engine

## Question

CLAUDE.md section 1 fixes six aspects - fleet, ledger, pantry, notes, dates, places - and says
plainly that a seventh is a ruling, not a refactor. Kevin said yes on 2026-09-01. This ticket
writes the ruling down and settles the shape.

The shape question: `.scratch/aspect-engine/` exists so a new aspect is rows in the master-data
tables rather than a new migration. Riding the engine gets the dev aspect generic CRUD voice tools
(aspect-engine ticket 06), generated list and detail screens (10), and a widget (08) for free.
Hand-rolling `projects` and `project_items` as bespoke tables duplicates all three surfaces and
then drifts from them.

Against riding it: the engine's migration waves (aspect-engine 21, 22) are not finished, so a new
aspect authored on the engine today may be the first thing to hit a gap the existing aspects have
not reached yet.

## Decide

1. Ruling recorded: the dev aspect exists, aspects are now seven. Name it (dev? projects? work?) -
   the name reaches voice copy and cannot be quietly changed later.
2. Engine record type, or bespoke tables? If bespoke, what is the written trigger for migrating it
   onto the engine later, so it does not become a permanent exception.
3. Does it get a widget page on the pager, or is it voice-and-detail-screen only?
4. Field vocabulary: what is a `project` versus a `project_item`? A repo with no open work - is it
   still a project? An archived repo?

## Verification

- The ruling lands in `memory/library/decisions.md` with the date.
- CLAUDE.md section 1's aspect list names seven.
- If it rides the engine, the record-type definition is expressed in the engine's own field-type
  vocabulary (aspect-engine ticket 03) and nothing new is added to that vocabulary here.
