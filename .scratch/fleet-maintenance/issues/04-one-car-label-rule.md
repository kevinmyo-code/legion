---
map: fleet-maintenance
ticket: 04
title: "One car label rule, across twelve surfaces"
type: grilling
status: resolved
status-detail: 2026-08-15
blockers: ["01"]
blocked-by: ["[[01-what-the-real-data-says]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# One car label rule, across twelve surfaces

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
| 2 | TELEMETRY drilldown header | `ui/TelemetryScreen.kt:117`, `:204` | `displayLabel` -> `name` -> literal `"THIS CAR"` |
| 3 | CARS roster rows | `ui/fleet/CarRows.kt:122-136` | `CarRows.carLabel` - **name-preferring** |
| 4 | CARS "follow the adapter" row | `ui/fleet/CarRows.kt:240-263` | `CarRows.carLabel` |
| 5 | CARS add/rename duplicate check | `ui/CarsScreen.kt:131` | `CarRows.carLabel` |
| 6 | DRIVING MODE HUD | `ui/DrivingModeScreen.kt:154`, `:377` | **raw `name`, uppercased** - a placeholder renders literally `THIS CAR` |
| 7 | Android Auto browse tree | `car/CarAspectSummaries.kt:37` | `name` -> `displayLabel` -> `"Fleet"` |
| 8 | OBD connect notification | `vehicle/ObdBluetoothManager.kt:628` | raw `displayLabel` |
| 9 | Spoken tool replies | `VehicleController.kt:388,412,443,450,461` | raw `displayLabel` |
| 10 | Voice ambiguity prose | `VehicleResolver.kt:136` | `displayName` - name-preferring **with a `"this car"` sentinel rejection**, `private`, no screen uses it |
| 11 | Advisor FLEET digest | `advisor/digest/FleetDigestBuilder.kt:132` | spec parts -> `name` -> `"vehicle"` |
| 12 | Capability-probe replies | `LiveToolbox.kt:3347,3363,3380,3395,3401,3405,3812` | raw `Vehicle.name` |

`displayLabel` (`VehicleController.kt:536-540`) is `year + make + model + trim`. **It never reads
`Vehicle.name`.** RENAME writes `Vehicle.name` and nothing else (`ui/CarsScreen.kt:186` ->
`correctVehicle(name =)`).

`CarRows.carLabel`'s own doc (`:113-120`) says it was made name-preferring on 2026-08-13 *because*
rename was invisible - so this bug was found once, fixed in one place, and left everywhere else.

## What has to be decided

1. **Is there one rule, or is a per-surface split legitimate?** There is a real argument that the
   HUD wants the short nickname and a spoken reply wants the full spec. If a split survives, it has
   to be a **named, deliberate two-function API**, not twelve ad-hoc call sites.
2. **What is the single rule?** `CarRows.carLabel`'s precedence is the strongest candidate:
   name blank -> spec; spec blank -> name; spec contains name -> spec; else name. Is the
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

---

## Answer (2026-08-15)

### The count reframed the ticket, again

The map's count-before-you-resolve rule paid for the fourth time. This ticket was charted as
"twelve surfaces". The real figure, re-counted after the day's changes:

| | Count |
|---|---|
| `displayLabel(` call sites | **24** |
| `CarRows.carLabel(` call sites | 4 |
| Raw `Vehicle.name` reads | ~16 |
| Distinct last-resort strings in use | **5** - `"this car"` (16), `"vehicle"`, `"This car"`, `"THIS CAR"`, `"an unnamed car"` |

Heaviest users: `LiveToolbox` (9), `VehicleController` (7), then `CarRows`, `ReminderController` (2
each). **The charted list also missed two surfaces entirely** - `location/ReminderController` and
`ui/fleet/ObdDeviceScreen`.

### The rule (Kevin, 2026-08-15)

**One rule, every surface, screen and speech alike.** No screen/speech split.

```
label(vehicle) =
    nickname blank, spec blank   -> "a car you haven't named yet"
    nickname blank               -> spec
    spec blank                   -> nickname
    spec CONTAINS nickname       -> spec            <- de-duplication clause
    otherwise                    -> "nickname (spec)"
```

where `spec` = `year make model` (**not** trim - see below) and `nickname` = `Vehicle.name`.

**The de-duplication clause is a refinement added on resolution, not part of Kevin's answer, and it
is load-bearing.** His own car is the counter-example: `name` = `1998 Jeep Cherokee`, spec =
`1998 Jeep Cherokee Limited`. The bare rule yields
`1998 Jeep Cherokee (1998 Jeep Cherokee Limited)`. `CarRows.carLabel` already carries exactly this
clause (`:122-136`), which is why the CARS pane rendered cleanly when the identity write-back landed
an hour ago. **It survives into the new rule unchanged.**

**Trim is excluded from the parenthesised spec.** `displayLabel` currently appends it, which is what
turned his row into `1998 JEEP CHEROKEE LIMITED` on screen. Year/make/model identifies the car;
trim belongs on a detail surface, and including it makes the de-duplication clause fail exactly
when the driver has named the car after its own spec - the common case for a car with no nickname.

### Where it gives when there is no room

**Two lines: nickname on top, spec beneath.** Never truncated, never dropped.

This is the shape `CarRows` already has - `carLabel` plus `carSpecPrefix` (`:143-146`) - so the
narrow case is not new work, it is the existing pattern generalised. Mission-control ticket 05's
budget stands: **a half tile holds about 7 characters of hero**, so a tile hero takes the nickname
line only and the spec goes to the caption slot.

### The `"this car"` sentinel is deleted, not filtered

**Stop writing it. A blank name means unknown.**

- `seedVehicle` stops putting a fake name in the `name` field. (It no longer persists at all as of
  ticket 13, so this only affects the in-memory placeholder it returns - but the placeholder is
  what several callers render.)
- **The two archived rows carrying `name = "this car"` get cleaned**, through a targeted write.
  They are archived and invisible today, which is exactly why they would survive to trap the next
  person.
- Every `it != "this car"` filter comes out. `VehicleResolver.displayName` (`:134-136`) is the only
  site that has one today; the rest never knew they needed it.

Rejected: keeping the magic value and filtering on read. It leaves a trap that the next label
surface will forget, which is precisely how this ticket's twelve became twenty-four.

### Two more calls, taken here rather than left implicit

1. **Never uppercase user-entered text.** `DrivingModeScreen` (`:154`, `:377`) renders
   `vehicleName.uppercase()`, which is how a placeholder became the literal shouted `THIS CAR`.
   Mission-control's typography uppercases **chrome** - headers, labels, tags. A car's name is
   **data**. Uppercasing is a chrome concern and must never be applied to a string the driver typed.
2. **One last-resort string, not five.** `"a car you haven't named yet"` on screen and in speech
   alike. `"vehicle"`, `"This car"`, `"THIS CAR"`, `"an unnamed car"` and the raw `obdMac` all go.

### Found on device while verifying the identity write-back (2026-08-15)

**A stale-parent bug, and it is this ticket's business because it makes a correct label look wrong.**
After `SYNC ID FROM VIN` wrote `Jeep`/`Cherokee`/`1998`/`Limited` to the row, returning to FLEET
still rendered `THIS CAR`. The CARS pane holds its state from when the surface loaded and does not
re-read on return from a drilldown. Switching tabs and back showed `1998 JEEP CHEROKEE LIMITED`
correctly.

So a write reported as successful on one screen was **not reflected on the screen that sent you
there** - the same family as the map's core defect, one layer up: not a write that did nothing, but
a write nobody could see. Whatever reload mechanism the build uses must cover the drilldown-return
path, and the on-device check is "change the identity, press BACK, read the parent" - **not** "change
it and switch tabs", which is the check that would have passed while the bug was live.

### Scope note

This resolves the label rule. It does **not** resolve ticket 04's full original framing:
the identity write-back half was built and shipped separately today (commit `b499169`), and it is
what put real make/model/year on Kevin's row in the first place. **The rule below is what stops the
next car without a decodable VIN from reading as `THIS CAR` anyway.**

**Unblocks ticket 12.**

### Verification

Binding on whoever builds this (L11):

1. **Re-count first.** The numbers above are from 2026-08-15; confirm them before editing, because
   they have already moved twice.
2. On the device, rename a car to something that is **not** a substring of its spec, and read
   **every reachable surface**: CARS roster, CARS follow-the-adapter row, FLEET CARS pane, FLEET
   tile, TELEMETRY header, driving mode HUD, OBD device screen, the OBD connect notification, and a
   spoken reply from `list_vehicles`.
3. **Name the surfaces that cannot be reached and why.** Android Auto's browse tree has never
   touched a head unit; say so rather than claiming coverage.
4. Confirm the drilldown-return path specifically, per the stale-parent bug above.
5. Confirm no surface uppercases the nickname.

### Assumptions ledger

- `traced`: all four counts, the per-file breakdown, the five last-resort strings, `CarRows.carLabel`'s
  existing de-duplication and sub-line pattern, `DrivingModeScreen`'s `uppercase()`,
  `VehicleResolver.displayName`'s sentinel filter, and that `displayLabel` appends trim.
- `on-device`: the stale-parent bug (observed directly, screenshotted, and worked around by
  switching tabs), and that the new rule's de-duplication clause produces the right string for
  Kevin's actual row.
- `reasoned`: that excluding trim from the parenthesised spec is right. It follows from the
  de-duplication clause needing to fire on the common case, but no other car in the roster has a
  trim on file to test it against.
- **Not built.** Nothing in this answer has been implemented.
