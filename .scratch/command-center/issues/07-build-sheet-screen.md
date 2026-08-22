---
map: command-center
ticket: "07"
title: "The build sheet exists on screen"
type: build
status: built
status-detail: "Built: build-sheet screen, spend parity with get_spend by test, service cost enters by hand. Notes have no column, said plainly. Owes the on-phone pass."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# The build sheet exists on screen

Survey: an entire store (`build_entries`) with a write tool, a read tool and a spend tool, and no
screen. `FleetScreen` loads `buildSheetCount` and never renders it. Also: `log_service` by hand
moves the maintenance clock but never creates a `service_records` row, so cost never enters by
hand and the fleet-spend panel keeps reporting "0 of N records have a cost".

## Build

1. A build-sheet surface under fleet: entries listed (same `BuildSheetController` reads), add-entry
   dialog (same write `log_build_entry` reaches), per-category spend using the same computation
   `get_spend` uses - never a parallel sum.
2. **Service record creation by hand**: the maintenance done-flow gets an optional cost+notes step
   that writes a real `service_records` row through the same path `log_service` uses, not just the
   anchor. Read `MaintenanceWrites.kt`'s own doc on why it currently does not, first - if there was
   a reason beyond omission, surface it rather than steamrolling it.

## Rules

- ADR 0035. Money is Long cents. Render buildSheetCount or delete the dead state load - no
  half-wired state.

## Verification

- Suite green both ways. On the phone: add a build entry, see spend move; complete a maintenance
  item with a cost and see it in service history WITH the cost.
