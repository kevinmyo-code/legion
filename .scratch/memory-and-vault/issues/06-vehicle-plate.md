---
map: memory-and-vault
ticket: "06"
title: "Vehicle plate"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Vehicle plate

## What

- `public.vehicles.plate text null`, added by guarded SQL (`api/vehicle_plate.py`) from an
  `ingest` migration, the `api/obd_arrival_index.py` shape; the test database gets it from the
  conftest hook.
- `VehicleSerializer.plate`: optional, nullable, **no default**, trimmed, upper-cased, at most 16
  characters. A PUT that leaves it out keeps the stored value.
- Phone compatibility (traced 2026-11-05): `DjangoVehicleRow` decodes with `ignoreUnknownKeys =
  true`, so the pull ignores the new field; the phone sends no vehicle PUT on the engine path
  today, and the one migration PUT omits `plate`, which DRF leaves alone.
- Kevin's value is set after deploy, through the API. It is never in code or tests.

## Verification

- pytest: set, read back, PUT without it keeps it, too long refused, openapi carries it.
