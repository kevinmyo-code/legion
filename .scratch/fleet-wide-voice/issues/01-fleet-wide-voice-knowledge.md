---
map: fleet-wide-voice
ticket: 01
title: "The assistant should know the whole fleet, not just the car it is sitting in"
type: build-spec
status: resolved
status-detail: "2 calls, Kevin, 2026-08-06"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# The assistant should know the whole fleet, not just the car it is sitting in

## Question

Kevin: "voice ai doesnt know theres 2 cars. it only knows the current selected car. it should know
across the whole fleet."

It is worse than that. Read 2026-08-06, all `traced`:

| Fact | Where |
|---|---|
| The base system instruction never names a car **at all** - no make, model, name, or count | `ai/AriaBrain.kt:200-230` (`assembleBase`) |
| No `list_vehicles` tool, no `switch_vehicle` tool | `service/LiveToolbox.kt`, 39 tools declared |
| Vehicle scoping is buried below the tool layer - controllers resolve it themselves | 22 `ActiveVehicle.current` call sites / 35 `VehicleController.currentVehicle` call sites |
| The data layer is **already** fully per-vehicle | 84 `vehicleId` references across the DAOs |

So the assistant does not "only know the current car" - it has no concept of a car at all, and every
fleet answer it gives is silently about whichever car `ActiveVehicle.current()` resolves to. Nothing
needs a schema change; the DAOs can already answer per-vehicle questions. The chokepoint is the
controller layer hardcoding "current".

---

## Resolution

### Call 1 - Scope: KNOW + QUERY ANY CAR

`list_vehicles`, plus an optional `vehicle` argument on every vehicle-scoped tool, threaded down as a
`vehicleId` override. "When did the second car last get an oil change" answers about the second car while
the Outlander stays active.

**Rejected: `switch_vehicle`.** It would have been far smaller (no controller changes), but switching
invalidates the cached base instruction and restarts the live voice socket mid-conversation via
`ACTION_CAR_SWITCHED` (`vehicle/ActiveVehicle.kt`, `notifyResolutionChanged`), so the driver hears the
seam every time they ask about the other car. **Rejected: awareness only** - it leaves every other
fleet tool silently answering for the active car, which is the confidently-wrong failure this ticket
exists to remove.

### Call 2 - Per-car persona keying: MAKE IT GLOBAL

`ai/CompanionProfile.kt` keys persona, name, voice, traits and avatar by `ActiveVehicle.current()`
(`:142`, `:164`, `:228`). CLAUDE.md §2 locked the opposite in the pivot: *"One global assistant
identity - cars are data, not identities. Per-car `CompanionProfile` keying and Midnight AI's
`CompanionIdentity` Zero-vs-car-self split are both dead."* The code never got cleaned up.

Its own doc comment (`:130-136`) still argues from two dead premises - *"CLAUDE.md §1 makes the PAID
companion the CAR itself"* and *"Free-tier Zero is unaffected"*. Billing is dead, Zero is dead, the
city-pop design language is dead. The rationale does not survive its own citations.

---

## §0 - THE SPLIT THAT MATTERS MOST

**Not every vehicle-scoped tool can take a `vehicle` argument.** Two categories, and treating them
alike reintroduces the exact bug this ticket removes.

**A. LIVE-HARDWARE tools** read the OBD dongle in real time. The dongle is physically plugged into
ONE car. There is no such thing as the second car's current coolant temperature while the dongle is in
the Outlander.

- `get_vehicle_data`, `get_health`, `check_readiness`, `get_codes`, `diagnose_codes`,
  `triage_symptom`, `check_cold_start`

These take **no `vehicle` argument**. If the model supplies one anyway, and it does not resolve to
the currently connected car, the tool **refuses in words** - `{"error": "not_connected_to_that_car"}`
plus a spoken-friendly message naming which car the dongle IS in. It must never fall back to
answering for the active car, and it must never answer with the requested car's name attached to
another car's live reading. Returning the active car's data under the wrong label is the failure
mode; silence is not.

**B. STORED-DATA tools** read Room, already keyed by `vehicleId`. These take the argument.

- `get_next_service`, `ask_maintenance`, `get_mpg`, `get_trend`, `list_car_tasks`, `add_car_task`,
  `complete_car_task`, `remove_car_task`, `list_build_history`, `log_build_entry`, `get_specs`,
  `check_recalls`, `lookup_vin`, `log_service`, `log_past_service`, `set_odometer`

A `vehicle` argument that matches no car resolves to nothing and the tool says so by name - it never
silently falls through to the active car. Omitted means the active car, which keeps every existing
utterance working unchanged.

---

## Build spec

### 1. `vehicle/VehicleResolver.kt` (new)

One place that turns whatever the model said into a vehicle, so 16 tools do not each invent their own
matching.

```kotlin
sealed interface VehicleMatch {
    data class Resolved(val vehicle: Vehicle) : VehicleMatch
    /** Named something, matched nothing. Carries the roster so the tool can say what DOES exist. */
    data class Unknown(val requested: String, val known: List<String>) : VehicleMatch
    /** Named something that matched more than one car - never guess between them. */
    data class Ambiguous(val requested: String, val candidates: List<String>) : VehicleMatch
}

/**
 * [spoken] is null/blank when the model omitted the argument, which means the
 * active car - that default is what keeps every existing utterance working.
 */
suspend fun resolveVehicle(context: Context, spoken: String?): VehicleMatch
```

Matching, in order, case-insensitive, against **non-archived** vehicles (`VehicleDao.getAll()`):
exact `name`; exact `obdMac`; exact `model`; then a contains-match across
`"$year $make $model $trim $name"`. First tier that yields exactly one match wins. A tier yielding
several returns `Ambiguous` rather than picking - two Outlanders must not silently collapse.

**Archived cars are excluded**, matching what `CarsScreen`'s roster shows by default. An archived
car matched by name returns `Unknown` with a message saying it is archived, not a bare "no such car"
- the driver asking about it is evidence it exists to them.

### 2. Controller threading

Every one of these gains an optional `vehicleId: String? = null` **as the last parameter**, defaulting
internally to today's `ActiveVehicle.current(context)` / `VehicleController.currentVehicle(context)`.
Default-null means no existing call site changes.

- `VehicleController` - `currentVehicle` gains a sibling `vehicleFor(context, vehicleId)`; do NOT
  change `currentVehicle`'s signature, 35 call sites depend on it
- `MaintenanceController`, `BuildSheetController`, `VehicleSpecController`, `CarToolbelt`,
  `DailyDriveLogController`, `MonthlyRecapController`, `YearlyWrappedController`, `ObdHistory`

**Do not thread it into** `TelemetryRecorder` or `ObdBluetoothManager`. Those write live hardware
data and must always key on the actually-connected car; an override there would file the Outlander's
telemetry under the second car.

### 3. `service/LiveToolbox.kt`

- **New tool `list_vehicles`**, no arguments. Returns every non-archived car: `name`, `year`, `make`,
  `model`, `trim`, whether it is the active one, whether the dongle is currently connected to it, and
  last-known odometer. This is the tool that makes the fleet knowable at all. Description must say it
  is the way to find out what cars exist, so the model reaches for it instead of assuming one car.
- **Category B tools** gain an optional `vehicle` string parameter. Description on each, verbatim
  shape: *"Which car, by name or model. Omit for the car currently being driven."*
- **Category A tools** gain **no** parameter and their descriptions gain: *"Reads the OBD dongle in
  the car it is plugged into right now. Cannot answer for any other car."*
- `VehicleMatch.Unknown` / `.Ambiguous` map to a JSON error naming the requested string and listing
  the known cars, so the assistant can say "you have the Outlander and the other car, which one" rather
  than answering wrongly.

### 4. `ai/AriaBrain.assembleBase()`

Append a fleet fragment after the driver profile:

```
The driver's fleet: <year make model ("name")>, <year make model ("name")>. They are currently
driving <name>. Tools that read stored records take a `vehicle` argument; tools that read the OBD
dongle only ever answer for the car it is plugged into. Never assume there is only one car.
```

Built from `VehicleDao.getAll()`, **never hardcoded and never illustrated with invented cars** - what
is actually registered on Kevin's device is not established here. MEMORY.md records only that the
Outlander is real (VIN, 5,242 samples) and that a placeholder row was corrected so it no longer
claims to be a Cherokee. Read the real roster off the device before writing any example string, and
degrade honestly: one car means one entry and no "fleet" framing, zero cars means omit the fragment
entirely rather than emitting an empty list. **This is a pre-injected context block, which
CLAUDE.md §7 says to avoid** - justified narrowly and the justification belongs in the code comment:
the roster is 2 rows, changes only when a car is added, and without it the model does not know a
fleet exists so it never calls `list_vehicles` in the first place. Pull-based tools cannot bootstrap
their own existence. Keep it to names and the active car; everything else stays pull-based.

`invalidateBase()` already exists and is already called on car switch, so the fragment stays current.

### 5. `ai/CompanionProfile.kt` - persona goes global

- `k(context, base)` and `identityString(context, base)` stop consulting `ActiveVehicle`; reads and
  writes use the flat key.
- **Do not just drop the per-car keys - Kevin's existing Alfred/Dorothy setup is stored under one.**
  On first global read, if the flat key is empty and a per-car key holds a value, promote the ACTIVE
  car's value to the flat key once, then read flat forever after. Losing his configured persona to a
  cleanup is not an acceptable outcome of this ticket.
- Delete the `:130-136` doc-comment rationale and replace it with why identity is global, citing
  CLAUDE.md §2. Do not leave the dead-premise argument in place as history.
- `avatarIdFor`/`avatarId`: `AvatarStudio` is dead (pivot, §2). Collapse to the flat `AVATAR_ID` and
  say so; do not keep a per-car avatar path for a feature that no longer exists.

### 6. Docs

- CLAUDE.md §2's "one global assistant identity" row gains a note that `CompanionProfile` now
  actually implements it, with the date. The row currently describes an intent the code contradicted.
- `memory/library/decisions.md` gets the 2026-08-06 entry: both calls, the §0 split, and the
  persona-promotion step.

---

## Tests

Unit, plain JVM where possible.

`VehicleResolverTest`
1. Blank/null `spoken` resolves to the active car.
2. Exact name match, case-insensitive.
3. Model match when the name does not match.
4. Contains-match across the assembled description ("grand cherokee").
5. Two cars sharing a model -> `Ambiguous`, never a pick.
6. Unmatched string -> `Unknown` carrying the full known roster.
7. Archived car excluded from matching, and its `Unknown` says archived.
8. Exact name beats a contains-match that would also hit another car (tier order).

`LiveToolbox` / controllers
9. A category B tool with `vehicle` omitted returns the active car's data (no behaviour change).
10. A category B tool with `vehicle` naming the other car returns THAT car's data while
    `ActiveVehicle.current` is unchanged after the call - assert the active car did not move.
11. A category A tool given a `vehicle` that is not the connected car refuses, and the refusal names
    the car the dongle IS in. **Assert it does not return the active car's readings.**
12. `list_vehicles` returns every non-archived car and flags exactly one active.

`CompanionProfileTest`
13. A value written under a per-car key and absent from the flat key is promoted on first read.
14. After promotion, switching the active car returns the SAME persona, name and voice.
15. A fresh install with nothing stored returns blank, not a crash.

## Verification gates

Binding, per CLAUDE.md §8 L11 - account for each as done / deferred-with-a-named-follow-up /
impossible-and-why before reporting this built.

1. `./gradlew compileDebugKotlin -Pnokey` clean (`JAVA_HOME` per CLAUDE.md §6).
2. `./gradlew testDebugUnitTest -Pnokey` green, 252 existing plus the new cases.
3. **`ActiveVehicle.current` must be unchanged by any category B call.** Assert it in a test, do not
   eyeball it - a tool that silently switches the active car would flip the driver's persona and
   voice as a side effect of a question.
4. **On device, by voice, with both cars registered.** MEMORY.md: nobody has ever asked the assistant
   a question and no voice tool call has ever run, so this is the first exercise of the tool path at
   all. Ask: what cars do I have; when did the second car last get serviced (while the Outlander is
   active); what is the coolant temp on the second car (must refuse, naming the connected car).
5. Confirm the persona did not change after the `CompanionProfile` edit - Alfred/Dorothy must survive
   the promotion. Check before and after on the device, not from the code.

## Out of scope

- `switch_vehicle` (declined, call 1).
- Per-car OBD history comparison or any cross-car aggregate. CLAUDE.md §7 bans comparative fleet
  data; that ban is about OTHER drivers' cars, but a cross-car feature is a separate call regardless.
- Archived-car resurrection from voice.
- Anything in `TelemetryRecorder`/`ObdBluetoothManager` (§2).
