# One car label rule, across twelve surfaces

Type: grilling
Status: open
Blocked by: 01

## Input from ticket 01 (2026-08-15) - cause identified, question narrowed

**The active vehicle is correct** (`active_vehicle.xml` is an explicit pick resolving to the Jeep),
so cause #3 below is **eliminated**. Cause #1 is not it either: `name` holds `1998 Jeep Cherokee`.

**It is cause #4.** `make`, `model` and `year` are all empty on the row, so `displayLabel` returns
the empty string and every raw-`displayLabel` surface falls through to its literal. The rename
worked; the identity underneath it is blank.

That means **question 2's resolution rule is necessary but not sufficient** - a name-preferring rule
fixes the twelve surfaces, and the car still has no make or model for `check_recalls` (ticket 12),
the maintenance seed, or any year/make/model lookup. The identity write-back is ticket 13.
Question 6's placeholder-collision worry is **closed**: the two `this car` rows are both archived.

## New consequence of ticket 13's fix (senior-dev review, 2026-08-15)

Ticket 13 stopped `seedVehicle` persisting a placeholder, which is correct - but it means
**child rows can now be written against a `vehicleId` that has no `vehicles` row.**
`VehicleSpecController.refreshFromVin` / `saveManual` and `BuildSheetController.add` all key off
`vehicleFor(...).obdMac`, so a VIN decode or a build-sheet entry logged against a never-registered
dongle lands in `vehicle_specs` / `build_entries` with no parent.

Assessed as **self-healing** - `obdMac` is stable, so a later registration attaches to the same key
and the orphan reunites with its car. Tagged `reasoned`, **not tested and not seen on device.**

**It sharpens this ticket rather than adding a new one**, because it is the same disease: Kevin's
Jeep already has a `vehicle_specs` row that has never reached its `vehicles` row. The write-back
this ticket owes has to work in both directions - decode-to-identity, and orphan-to-parent - or the
gap just moves.

## Question

Kevin renamed his car. The screen still says `THIS CAR`.

**The count came first, and it reframed the ticket.** This is not one broken screen. There are
**four different car-label resolution rules across twelve surfaces**, and the RENAME button feeds
only three of them.

| # | Surface | file:line | Rule |
|---|---|---|---|
| 1 | FLEET root, CARS pane first row | `ui/FleetScreen.kt:270`, `:758` | raw `displayLabel`, `.ifBlank { "This car" }` - **never reads `name`** |
| 2 | TELEMETRY drilldown header | `ui/TelemetryScreen.kt:117`, `:204` | `displayLabel` → `name` → literal `"THIS CAR"` |
| 3 | CARS roster rows | `ui/fleet/CarRows.kt:122-136` | `CarRows.carLabel` - **name-preferring** |
| 4 | CARS "follow the adapter" row | `ui/fleet/CarRows.kt:240-263` | `CarRows.carLabel` |
| 5 | CARS add/rename duplicate check | `ui/CarsScreen.kt:131` | `CarRows.carLabel` |
| 6 | DRIVING MODE HUD | `ui/DrivingModeScreen.kt:154`, `:377` | **raw `name`, uppercased** - a placeholder renders literally `THIS CAR` |
| 7 | Android Auto browse tree | `car/CarAspectSummaries.kt:37` | `name` → `displayLabel` → `"Fleet"` |
| 8 | OBD connect notification | `vehicle/ObdBluetoothManager.kt:628` | raw `displayLabel` |
| 9 | Spoken tool replies | `VehicleController.kt:388,412,443,450,461` | raw `displayLabel` |
| 10 | Voice ambiguity prose | `VehicleResolver.kt:136` | `displayName` - name-preferring **with a `"this car"` sentinel rejection**, `private`, no screen uses it |
| 11 | Advisor FLEET digest | `advisor/digest/FleetDigestBuilder.kt:132` | spec parts → `name` → `"vehicle"` |
| 12 | Capability-probe replies | `LiveToolbox.kt:3347,3363,3380,3395,3401,3405,3812` | raw `Vehicle.name` |

`displayLabel` (`VehicleController.kt:536-540`) is `year + make + model + trim`. **It never reads
`Vehicle.name`.** RENAME writes `Vehicle.name` and nothing else (`ui/CarsScreen.kt:186` →
`correctVehicle(name =)`).

`CarRows.carLabel`'s own doc (`:113-120`) says it was made name-preferring on 2026-08-13 *because*
rename was invisible - so this bug was found once, fixed in one place, and left everywhere else.

## What has to be decided

1. **Is there one rule, or is a per-surface split legitimate?** There is a real argument that the
   HUD wants the short nickname and a spoken reply wants the full spec. If a split survives, it has
   to be a **named, deliberate two-function API**, not twelve ad-hoc call sites.
2. **What is the single rule?** `CarRows.carLabel`'s precedence is the strongest candidate:
   name blank → spec; spec blank → name; spec contains name → spec; else name. Is the
   spec-contains-name clause right, or surprising?
3. **The `"this car"` sentinel.** Only `VehicleResolver.displayName` rejects it. `seedVehicle`
   (`:1039`) writes it as a literal name, so it is a **magic value masquerading as user data**.
   Should it be rejected everywhere, or should the seed stop writing it and leave `name` blank?
   The second is cleaner and is a data change, not just a code change.
4. **What is the last-resort string** when a car genuinely has neither name nor spec? Today it is
   variously `"This car"`, `"THIS CAR"`, `"Fleet"`, `"vehicle"`, `"an unnamed car"`, and the raw
   `obdMac`. Pick one, or pick one per register (screen vs spoken).
5. **Casing.** Mission-control's typography uppercases headers. Surface 6 uppercases the *data*,
   which is why the placeholder shouts. Decide whether uppercasing is a chrome concern that must
   never be applied to a user-entered string.
6. **The placeholder collision.** Ticket 01 answers whether Kevin's Jeep row *is* the seeded
   placeholder. If it is, the fix is partly data (`confirmed`, make/model) and not only code -
   and `check_recalls` is gated on `isConfirmed` (`LiveToolbox.kt:1884-1893`), so the same root
   cause is also why ticket 12's recall button might refuse to work.

## Verification

Whatever rule is chosen, **the check is on the device, not in the diff**: rename the car to
something unmistakable and confirm every one of the twelve surfaces that can be reached shows it.
Name the surfaces that cannot be reached (Android Auto has never touched a head unit) and say so
rather than claiming coverage.
