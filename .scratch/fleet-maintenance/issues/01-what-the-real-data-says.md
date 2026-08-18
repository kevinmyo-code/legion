# What the real data on the phone actually says

Type: task
Status: resolved (2026-08-15)

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

---

## Answer (2026-08-15)

Pulled `legion_database` **plus `-wal` and `-shm`** off the device with `adb exec-out run-as`.
The WAL was 428KB and newer than the main file (12:21 today vs the previous evening) - **pulling the
main file alone would have read a stale database.** All three sizes matched `ls -l` on the device
exactly. `PRAGMA integrity_check` = `ok`, `user_version` = **19**, matching MEMORY.md.

Read-only throughout. No `uninstall`, no `pm clear`, no writes to the device.

### Headline

**Kevin's Jeep has no identity and no odometer, and seven of its ten maintenance items are
invisible on screen.** The single fact that breaks the most downstream behaviour:

> `currentMileage` for the Jeep evaluates to **0**, while its service anchors sit at **118,483** (odometer values in this section are invented, offsets preserved for the public record).

Every mileage-based due calculation on the car is computing against an odometer of zero.

### 1. `vehicles` - five rows, not one

| `obdMac` | `name` | make | model | year | conf | onbd | arch | odoBaseline | tripMiles |
|---|---|---|---|---|---|---|---|---|---|
| `12:34:56:11:22:33` | **`1998 Jeep Cherokee`** | **empty** | **empty** | **0** | 1 | **0** | 0 | **0** | 0.0 |
| `imported-mitsubishi-outlander-2020` | `Outlander` | Mitsubishi | Outlander | 2020 | 1 | 1 | 0 | 73577 | 12.61 |
| `car:c352c532-...` | `F-150` | Ford | F-150 | 2017 | 1 | 1 | 0 | 0 | 158.53 |
| `78:9A:BC:11:22:33` | `this car` | empty | empty | 0 | 0 | 0 | **1** | 0 | 0.02 |
| `default` | `this car` | empty | empty | 0 | 0 | 0 | **1** | 0 | 0.0 |

- **Two rows named literally `this car`.** Both archived, so neither is the one Kevin sees.
- **The Jeep is a real registered row, not the placeholder.** Charting decision 2's collision worry
  is resolved: the placeholder rows are the two archived ones.
- **`confirmed = 1`** on the Jeep, so ticket 12's recall gate passes.
- **`onboarded = 0`** on the Jeep, despite it having ten seeded maintenance items.

### 2. Which row is active - answered, and it is the right one

`shared_prefs/active_vehicle.xml` holds `vehicle_id = 12:34:56:11:22:33`. **An explicit pick,
resolving to the Jeep.** Ticket 04's cause #3 (wrong car) is **eliminated**.

The `THIS CAR` reading has a different cause, and the data names it:

**`make`, `model` and `year` are all empty, so `displayLabel` returns the empty string.** Every
surface that uses raw `displayLabel` falls through to its literal - `"This car"` on FLEET's CARS
pane, `"THIS CAR"` on TELEMETRY. `Vehicle.name` holds `1998 Jeep Cherokee` and those surfaces never
read it. **Ticket 04's premise is confirmed and its cause is now specific: not a resolution-order
preference, an empty identity.**

**The identity is not actually unknown - it is decoded and sitting in another table.**
`vehicle_specs` for the same `vehicleId`:

| field | value |
|---|---|
| `vin` | `1FAKEVIN000000001` |
| `engineCylinders` / `displacementL` / `engineConfig` | 6 / 4.0 / In-Line |
| `manufacturer` / `plantCity` | `Fca us llc` / `Toledo` |
| `bodyClass` | SUV/MPV |
| `decodedAt` | 2026-07-26 14:19:49 |

**The VIN decode succeeded three weeks ago and never wrote back to the vehicle's identity row.**
That is the root cause of the label bug *and* of ticket 12's recall problem.

**Corrected 2026-08-15** (the original wording here asserted a mechanism I had not read):
`check_recalls`' `confirmed` gate passes, because `confirmed = 1`. What follows is **not** an HTTP
request with empty parameters - `VinDecoder.fetchRecalls` guards at its own entry
(`if (year <= 0 || make.isBlank() || model.isBlank()) return@withContext emptyList()`,
`VinDecoder.kt:98-99`), so no request is made and an empty list comes back.

The consequence is the same and is the reason it matters: **an empty list is indistinguishable from
"no open recalls."** The app would report a clean bill of health on a car it never asked about.

### 3. `maintenance_items` - 54 rows across 5 cars; the Jeep's 10

| serviceName | miles | months | lastDoneMileage | lastDoneDate | updatedAt |
|---|---|---|---|---|---|
| Air Filter Replacement | 30000 | 30 | null | null | 07-18 10:24:12 |
| Spark Plug Replacement | 30000 | null | null | null | 07-18 10:24:12 |
| Tire Rotation | 6000 | 6 | null | null | 07-18 10:24:12 |
| Brake Fluid Flush | null | 24 | null | null | 07-18 10:24:12 |
| Coolant Flush | 30000 | 24 | null | null | 07-18 10:24:12 |
| Differential Fluid Service | 30000 | null | null | null | 07-18 10:24:12 |
| Transmission Fluid Service | 30000 | null | null | null | 07-18 10:24:12 |
| **Oil Change** | **3000** | 3 | **118483** | **null** | 08-12 15:50:16 |
| **Brake Pads** | null | null | 118483 | null | 08-12 15:52:52 |
| **Brake Fluid** | null | null | 118483 | null | 08-12 15:52:52 |

**The 3,000 is confirmed in the data**, exactly one row app-wide has it, and it is Kevin's oil. Its
3-month companion interval is equally aggressive.

App-wide counts: **49 of 54 items have no anchor at all** (`isUnknown`), `neverDone` is **0 rows** -
never once used - 5 rows have no `intervalMiles`, 17 no `intervalMonths`, and **no row has
`updatedAt = 0`**, so every row was written by app code, none by a raw migration.

**Ticket 08's predicted orphan rows exist in the wild.** `Brake Pads` and `Brake Fluid` were created
by a log whose name matched no seeded item, silently, as anchor-only rows with no interval. And
`Brake Fluid` now sits directly beside the seeded `Brake Fluid Flush` - **the same service, two
rows, neither complete.** This is no longer a hypothetical risk in ticket 08; it is a repair job.

**Duplicate-concept rows are rampant across the other cars too**, from repeated re-seeding under
slightly different LLM-chosen names: `Air Filter` / `Air Filter Replacement` / `Engine Air Filter`;
`Axle Fluid` / `Axle Lubricant` / `Axle Lubricant Service`; `Transmission Fluid` / `Transmission
Fluid Service`; `Transfer Case Fluid` / `Transfer Case Fluid Change`. The Outlander alone carries
**14 rows** for what is maybe eight distinct services. `canonicalizeAndDedupe`'s ten-keyword table
is not holding.

### 4. What the maintenance drilldown actually renders right now

Computed from the traced data through the traced code (`buildDueRows` then `toDueRow`, with
`isUnknown`, `isDue`, `formatRemaining`), at `currentMileage = 0`:

- **`buildDueRows` drops all seven unanchored items.** Tire Rotation, Coolant Flush, Spark Plugs,
  Air Filter, Diff Fluid, Trans Fluid, Brake Fluid Flush - **invisible, not merely unsorted.**
- **Three rows survive**, and none of them is due:

| row | value | sub-line |
|---|---|---|
| Oil Change | **`in 121,450 miles`** | `every 3,000 mi - last at 118,483` |
| Brake Pads | `-` | `no interval on file` |
| Brake Fluid | `-` | `no interval on file` |

`3,000 - (0 - 118,483)` = 121,483, floored to a multiple of 50. **The oil is reported due in
over a hundred thousand miles.** Kevin's "the drilldown is not what I want" is a considerable
understatement.

### 5. The odometer has never been set on this car, and the estimator has never moved it

`odometerBaseline = 0`, `odometerBaselineAt = never`, `tripMilesSinceBaseline = 0.0`,
`lastOdometerPromptAt` = 2026-08-13 13:18:21 (so the monthly nag **has** fired).

Yet the OBD data is substantial - `obd_samples` holds **18,645 rows**, of which **6,957 are the
Jeep's**, spanning 2026-07-15 to 2026-08-12, including **938 speed (`010D`) samples**. So the
adapter genuinely is in the port on most drives, as Kevin said at charting.

**Exactly one `TRIP_MILES` row exists for the Jeep** (20.72 mi, 2026-07-30), against eleven
app-wide. The `MIN_TRIP_MILES` / `MIN_TRIP_GALLONS` gate is discarding nearly every drive.

**And `tripMilesSinceBaseline` is 0.0 despite that finalized 20.72-mile drive**, which should have
accumulated onto it tick by tick on the same code path. The two accumulations ticket 10 flagged as
"can legitimately disagree" are not disagreeing - **one of them is flat zero.**

Adjacent data-integrity finding: `default` and `imported-mitsubishi-outlander-2020` hold **5,242
`obd_samples` each, identical values at identical timestamps** - a duplicated import, with
`midnight_import.xml` present in shared_prefs. Not this map's problem, but somebody should know.

### 6. `service_records` - both rows, and they disagree with the anchor

| id | vehicleId | serviceName | mileage | date | cost |
|---|---|---|---|---|---|
| 1 | `12:34:56:11:22:33` | Oil Change | 118,331 | 2026-07-29 21:32:25 | **null** |
| 2 | `12:34:56:11:22:33` | Oil Change | 118,374 | 2026-08-12 15:50:02 | **null** |

Both on the right car. **`cost` is null on both**, as predicted - fleet spend has nothing to sum.

**The two tables disagree by 109 miles, fourteen seconds apart.** Record 2 says the oil was done at
118,374 at 15:50:02; the Oil Change anchor says 118,483, stamped 15:50:16. `logServiceDirect` writes
both from the same `mileage` local, so **they cannot both have come from that call** - and the
anchor's `lastDoneDate` is **null**, which `logServiceDirect` never leaves (it always sets `now`).
Traced conclusion: a `log_past_service` backfill landed 14 seconds after the `log_service`,
overwrote the anchor mileage, and left the date null. **Ticket 08 must account for a backfill that
silently overwrites a precise record's anchor with a remembered approximation.**

### 7. What I could NOT explain, and what I ruled out

**How the Jeep's row lost its identity and odometer.** On 2026-07-18 it had a year/make/model - it
must have, because `applyServiceIntervals` seeded ten items from a prompt built out of those fields,
and `onboardPendingVehicles` skips blank make/model. On 2026-08-12 it had an odometer near 118,374,
because `logServiceDirect` derived that record's mileage from `currentMileage`. Today all five
fields are empty or zero and `onboarded` is back to 0.

Ruled out by reading, not by assuming:

- **The 2026-08-13 migrations 16 to 17, 17 to 18, 18 to 19.** All three touch `ledger_transactions`
  and `category_rules` **only**. They never mention `vehicles`. Not the cause.
- **`correctVehicle`** (the rename, stamped 2026-08-13 14:37:54). It builds `existing.copy(...)` and
  coalesces every field, so it preserves the odometer and cannot blank make/model. It could only
  have renamed a row that was **already** empty.
- **`registerDirect`.** Preserves the odometer explicitly (`:98-100`) and rejects blank make/model at
  the door (`:86`). Cannot produce this row. **Worth noting anyway: it builds a fresh `Vehicle` and
  silently drops `voiceName`, `personaTraits`, `trim`, `archived` and `lastOdometerPromptAt`** - a
  data-loss path nobody has ticketed.

**This is a new ticket, not a footnote.** A vehicle row losing its identity and odometer in normal
use is a data-loss defect that will undo any fix this map ships. Charted as ticket 13.

### Consequences for the map

| Ticket | Effect |
|---|---|
| 04 (car label) | Cause **narrowed**: not resolution order, an **empty identity** whose decode already exists in `vehicle_specs`. Wrong-active-car eliminated. Adds: write the decode back |
| 12 (recall button) | **Worse than charted.** `confirmed = 1` so the gate passes and NHTSA gets empty params - it will confidently report no recalls. Blocked on the identity fix, not just on 04 |
| 08 (name matching) | Promoted from risk to **repair**: the `Brake Fluid` / `Brake Pads` orphans exist, plus a backfill that overwrote a precise anchor and nulled its date |
| 09 (drilldown) | Confirmed with numbers: **7 of 10 items invisible**, oil due in 121,450 miles |
| 10 (odometer) | **The estimator has never accumulated on this car.** Manual entry is not an improvement, it is the only mechanism that will work at all |
| 06 (seeded guesses) | 3,000 confirmed as a single row; **`neverDone` has never been used once** in 54 rows; duplicate-concept rows show re-seeding is actively corrupting the schedule |
| 11 (cost) | `cost` null on both rows, confirmed |
| **13 (new)** | The Jeep row lost its identity and odometer in normal use. Unexplained |

### Assumptions ledger

- `traced` (read off the pulled database, values quoted above): every table row, every count, the
  active-vehicle preference, the `vehicle_specs` decode, all timestamps to the second.
- `traced` (read off source): migrations 16 to 19 touch no vehicle table; `correctVehicle`,
  `registerDirect`, `setOdometer`, `logServiceDirect`, `isUnknown`, `isDue`, `buildDueRows`,
  `toDueRow`, `formatRemaining`.
- `reasoned`: the three rendered drilldown rows and the `in 121,450 miles` figure are **computed**
  from traced data through traced code. **Not yet seen on screen.** Confirming them against the
  running app is the one check this ticket owes.
- `reasoned`: that a `log_past_service` backfill produced the 118,483 / null-date anchor. The
  fingerprint fits exactly (only that path writes an anchor without a date) but no log confirms it.
- `reasoned`: that the Jeep had a valid identity on 2026-07-18 and a valid odometer on 2026-08-12.
  Inferred from what the seeding and logging code paths require, not from a stored history.

