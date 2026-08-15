# What the real data on the phone actually says

Type: task
Status: open

## Question

Nothing on this map should be charted on inference about Kevin's database when the database is
twenty minutes away. Pull it and read it.

**This is ticket 01 because L15 and the 2026-08-13 ledger session both say the same thing: four
bugs were found by looking at the data, none by 955 tests.** Every downstream ticket here reasons
about rows nobody has looked at.

## What to pull

`adb exec-out` the Room database off the device (**never `adb shell cat`** - it corrupts a binary
pull; compare the pulled size against `ls -l` on the device). `adb` is not on PATH; see the
`wireless-adb-available` memory note for the binary path. There is no `sqlite3` on the device, so
the file has to come to the machine.

**Never `adb uninstall`, never `pm clear`.** Read-only operation.

## What to answer

1. **`vehicles`** - every row, every column. Specifically:
   - How many rows? Which are `archived`?
   - For each: `obdMac`, `name`, `make`, `model`, `year`, `trim`, `confirmed`, `onboarded`.
   - Is there a row whose `name` is literally `"this car"`? Is there more than one?
   - Is Kevin's real Jeep a row he registered, or the placeholder seed he renamed in place?
     `VehicleController.seedVehicle` (`:1036-1044`) sets `name = "this car"`; `Vehicle.kt:42-48`
     says the no-OBD placeholder is itself a 1998 Jeep Cherokee. **These are indistinguishable
     from the outside and that is the whole problem.**
   - `odometerBaseline`, `odometerBaselineAt`, `tripMilesSinceBaseline`, `lastOdometerPromptAt`.
     Has an odometer ever been set? What does `currentMileage` evaluate to?
2. **Which row does `ActiveVehicle.current` resolve to right now?** Explicit pick, connected dongle
   MAC, or the `"default"` fallback (`ActiveVehicle.kt:57-60`). If it resolves to a different row
   than the one Kevin renamed, that alone explains the "THIS CAR" reading and ticket 04 changes
   shape.
3. **`maintenance_items`** - every row for every vehicle. `serviceName`, `intervalMiles`,
   `intervalMonths`, `lastDoneMileage`, `lastDoneDate`, `neverDone`, `updatedAt`.
   - **Is there an oil row with `intervalMiles = 3000`?** Confirm the reported symptom against the
     data rather than the screenshot.
   - How many items were seeded? Do they match the 10-entry `SERVICE_KEYWORDS` canonical set
     (`VehicleController.kt:71-82`)?
   - How many have `neverDone = true`? How many have both anchors null (the "unknown" class)?
   - Is `updatedAt` non-zero on any row - i.e. has anything ever been edited after seeding?
4. **`service_records`** - both rows Kevin mentioned. `serviceName`, `mileage`, `date`, `cost`,
   `vehicleId`. **`cost` is expected to be null on every row** (no code path populates it); confirm.
   Does `vehicleId` match the active vehicle's `obdMac`, or an orphaned one?
5. **`obd_samples`** - roughly how many `TRIP_MILES` rows exist, over what date range? This is the
   only evidence for whether the odometer estimator has ever accumulated anything real, and ticket
   10 needs it.

## Why this blocks so much

- Ticket 04 (car label) cannot tell a rename-not-read bug from a wrong-active-car bug without (2).
- Ticket 06 (seeded intervals are guesses) needs to know how many rows would be flagged, and
  whether any have already been hand-edited.
- Ticket 08 (name matching) needs the real `serviceName` strings on both tables to know whether
  they already agree.
- Ticket 10 (odometer) needs (5) to know whether the estimator is producing anything at all.

## Answer format

A table per entity, real values, redacted only where Kevin says so. Tag every claim `traced` (read
off the pulled DB) or `reasoned`. **A claim about a row nobody read is not an answer to this
ticket.**
