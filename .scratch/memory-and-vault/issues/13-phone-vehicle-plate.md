---
map: memory-and-vault
ticket: "13"
title: "Phone: plate on the vehicle"
type: build
status: open
status-detail: ""
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
