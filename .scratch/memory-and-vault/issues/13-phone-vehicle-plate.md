---
map: memory-and-vault
ticket: "13"
title: "Phone: plate on the vehicle"
type: build
status: built
status-detail: "phone built 2026-11-05: plate on vehicles_replica (Room v79) and the Specs screen, written by PUT to the engine; owed: A25 run, Kevin sets his plate"
blockers: ["06"]
blocked-by: ["[[06-vehicle-plate]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Phone: plate on the vehicle

## What

- `DjangoVehicleRow` gains `plate: String? = null`; the vehicle screen shows it and edits it
  through the engine.
- Fleet tools may say the plate when asked (it is ordinary data, ruling 5).

## Verification

- JVM: the row decodes with and without `plate`.
