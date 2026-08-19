---
map: hands-and-senses
ticket: 01
title: "Clear DTCs: fleet's first write to the car"
type: grilling
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Clear DTCs: fleet's first write to the car

## Question

LEGION reads codes (`ObdBluetoothManager.getDtcCodes()`, Mode 03 at `ObdBluetoothManager.kt:470`)
but cannot clear them. Clearing is OBD Mode 04, one `sendCommand("04")` on plumbing that already
exists. The command is trivial; the decisions are not, because this is the app's first write to
the vehicle and Mode 04 is destructive three ways: it erases stored codes, erases freeze frame,
and resets readiness monitors and learned fuel trims. Reset monitors mean a failed emissions
inspection until full drive cycles complete.

Decide:

1. **The confirm turn.** Standing preference: destructive actions need an explicit confirm. What
   does Alfred say before clearing - does he always recite the codes about to be lost and the
   readiness warning, or only warn on the first use? Is "clear my codes" ever accepted from a
   one-shot, or only inside a live session?
2. **Snapshot before erase.** After Mode 04 the ECU forgets everything, so LEGION must latch the
   codes and freeze frame into its own history FIRST. Which table - the existing DTC-event shape
   the recap layer reads, or a new `cleared_at` marker on existing rows? Does the maintenance log
   get an entry ("codes cleared at N miles")?
3. **Surfaces.** Voice-only, or also a button on the fleet UI next to where codes render? The
   recall checker shipped both; is that the pattern?
4. **Failure honesty.** Mode 04 can fail silently on some ECUs (the code returns after key cycle
   because the fault is still present). Does LEGION re-read codes immediately after clearing and
   report what it sees, rather than reporting the send succeeded? (The fleet-maintenance map's
   core defect class was the silent no-op - the assistant reporting a change it could not have
   made. Same trap here.)

This ticket carries the build spec once resolved - it is small enough that it does not graduate a
build effort.

## Answer

Resolved 2026-08-16. Decisions 1-4 settled by Kevin in session; 5-10 taken by Stark under Kevin's
standing "you should not be coming to me about low-level stuff" (same session). Code facts below
are `traced` against the tree on 2026-08-16 unless tagged otherwise.

### D1. Clearing is a TRANSACTION, not a send. (Kevin)

`clear_codes` is **snapshot -> send -> re-read -> report what the re-read returned**. The re-read
is part of the operation and is not configurable.

**"Cleared" may never be spoken off the send.** The reason is mechanical, not stylistic:
`sendCommand` returns `""` on failure (`ObdBluetoothManager.kt:779`) and `Elm327Io.exchange`
returns whatever arrived on timeout without throwing (`Elm327Io.kt:22-23`) because
`readUntilPrompt` polls `available()` and never blocks on `read()` (`Elm327Io.kt:43-58`). A quiet
link and a successful clear are the same value at that seam. This is the defect already filed as
android-auto ticket 13, and option "send and ack" would have walked straight into it.

A clean re-read proves **the erase took**, not that the fault is gone - many ECUs only re-set a
code after a completed drive cycle. The register must not overclaim there (see D9).

### D2. Five outcomes. The `44` ack is diagnostic, never dispositive. (Kevin)

```kotlin
enum class ClearOutcome { NOTHING_TO_CLEAR, CLEARED, RETURNED, UNVERIFIED, REFUSED }
```

| Outcome | Trigger | Command sent? |
|---|---|---|
| `NOTHING_TO_CLEAR` | snapshot Mode 03 returns empty | **no** |
| `REFUSED` | link quiet or ECU rejects, **before** the send | **no** |
| `CLEARED` | re-read returns empty | yes |
| `RETURNED` | re-read still returns codes (names the survivors) | yes |
| `UNVERIFIED` | send went out, re-read failed or went quiet | yes |

- **`NOTHING_TO_CLEAR` is a state, not an error.** Sending Mode 04 against a car with nothing
  stored resets readiness monitors for zero benefit. Refuse early.
- **A partial clear is `RETURNED`**, naming which codes survived. There is no "partially cleared".
- **`UNVERIFIED` does not exist anywhere in this app today** and is the state the quiet-link
  defect proves is needed.
- **`44` may distinguish `REFUSED` from `UNVERIFIED` and may be logged. It may never upgrade a
  sentence.** An ECU that acks and keeps the code is exactly what D1 exists to catch.

**Mode 04 is excluded from the PID-silence counter.** `sendCommand` increments
`consecutivePidSilence` for every non-`AT` command (`ObdBluetoothManager.kt:782-801`) and trips
`reinitProtocolLocked()` (`ATPC`/`ATSP0`/`0100`, `:836-841`) at threshold. Renegotiating the bus
mid-write-transaction is wrong. Exclusion is by explicit opt-out on the call, not by widening the
`AT` prefix test.

### D3. New table `code_clear_events`. Room v21 -> v22, additive. (Kevin)

`code_events` (`CodeEvent.kt:18-28`) holds the right *snapshot* shape but has **no update, no
delete, no tombstone** (`CodeEventDao.kt:9-32`) and **no field that can mean "cleared"**. Two
rejected alternatives, for the record:

- **Reuse `code_events` alone** is silently wrong on screen: `FleetScreen` renders "STORED CODES"
  from `getAll(...)` (`:310`, `:355-357`, `:798-821`) and would keep asserting a car stores a code
  it was just told to erase.
- **A `clearedAt` column on `code_events`** cannot answer its own question. A clear is an event at
  time T, not a property of rows written weeks earlier; stamping "every row whose codes are a
  subset of the snapshot" retroactively rewrites observations that were true when made.

Only a dedicated table can hold the D2 outcome, which after D1 is the entire point.

```kotlin
@Entity(tableName = "code_clear_events")
data class CodeClearEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,              // Vehicle.obdMac, same key as code_events
    val timestamp: Long,                // epoch ms, moment of the send
    val mileage: Int? = null,           // VehicleController.currentMileage, ESTIMATE (see below)
    val codesBeforeJson: String,        // JSON array, the call-2 snapshot
    val freezeFrameJson: String = "",   // JSON object, Mode 02, "" if unavailable
    val codesAfterJson: String = "",    // JSON array, the re-read; "" when never read
    val outcome: String,                // ClearOutcome.name
    val ackRaw: String = "",            // raw Mode 04 response, diagnostic only
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
)
```

- **Sync `Mode.UNION`, no `deleted` tombstone.** Append-only falsifiable facts about the car, same
  posture as `code_events`.
- **The snapshot is NOT double-written to `code_events`.** It would inflate
  `MonthlyRecap.codeEventCount` (`FleetDrilldowns.kt:903`) with a read that discovered nothing new.
- **`mileage` carries the same unlabelled-estimate caveat `CarToolbelt.kt:100-105` already
  documents for `CodeEvent.mileage`.** Not fixed here; it is the same known gap, not a new one.

**Migration:** additive `CREATE TABLE` only. Per CLAUDE.md §5 the SQL must be copied **verbatim
from the kapt-generated migration**, not hand-written from the block above. `exportSchema = true`,
schema JSON committed under `app/schemas/`, no destructive fallback.

`MEMORY.md` says Room is v20. The tree says `version = 21` (`CarDatabase.kt:187`). The code is the
authority; MEMORY.md needs the correction.

### D4. Confirm turn: garage shape, informed prompt, always recited, live-session only. (Kevin)

Shape copied verbatim from the existing precedent - `confirmed: Boolean` as a **required** tool
param, model instructed to call `false` first and `true` only after a yes in the very next turn,
gate living in **pure Context-free controller code** so it is unit-testable
(`GarageController.kt:81-111`, declaration `LiveToolbox.kt:1091-1106`; `manage_grocery`'s `finish`
is the second instance).

1. **Call 1 (`confirmed=false`) performs the snapshot read and may end the operation without ever
   asking.** Empty -> `NOTHING_TO_CLEAR`. Quiet link -> `REFUSED`. LEGION never asks "shall I
   clear?" about a car with nothing to clear or a car that is not answering.
2. **Call 2 (`confirmed=true`) re-reads the snapshot immediately before sending.** The call-1 read
   feeds the *prompt*; the call-2 read feeds the *record*. Turns can be a minute apart and
   `codesBeforeJson` must say what was actually erased.
3. **The warning is recited every time, never first-use-only.** The codes differ each time so it is
   not boilerplate, and a "warned once" flag would need to persist and sync across two phones -
   precisely the state that goes stale and silently stops warning.
4. **Live session only.** Not a bolt-on: a two-turn confirm needs a "very next turn" and a one-shot
   has none. `clear_codes` joins `CATEGORY_A_TOOLS` (`LiveToolbox.kt:1519-1522`) behind
   `refuseIfNotConnectedCar`, which it needs anyway for the live OBD link.
5. **Engine running warns, does not gate.** RPM is already a live PID. When RPM > 0 the confirm
   prompt gains one clause noting some ECUs refuse a clear while running. A hard gate would block
   legitimate clears on cars that accept it, and `REFUSED` already covers an ECU that says no.

### D5. Surfaces: both, one shared gate. (Stark)

The recall checker is the pattern and it is followed exactly: voice tool plus UI, both funnelling
through **one** gate function, the way `VehicleSpecController.recalls(...)` owns the gate for
`check_recalls` (`LiveToolbox.kt:899-908`, `:1443`, `:1957-1981`) and `VehicleSpecsScreen`
(`:165-169`).

- **New `vehicle/DtcClearController.kt`** owns the whole transaction and the confirm gate. Pure,
  Context-light, unit-testable against a fake `ObdTransport`. Neither caller reimplements a step.
- **Voice:** one new tool, `clear_codes`.
- **UI:** an action on the existing "STORED CODES" block in `FleetScreen`'s UPLINK pane
  (`:798-821`), opening an `AlertDialog` in the `DeleteCompanionDialog` shape
  (`CompanionRows.kt:261-271`). **The dialog body is generated by the same controller function
  that generates the voice confirm prompt** - one wording, two renderers, so they cannot drift.
- **No new screen.** `ui/DtcSheet` is referenced in three KDocs but the file no longer exists, and
  `FleetScreen.kt:806-810` records that no faults drilldown exists. Building one is out of scope
  here; if the effort wants it, it is a separate ticket.

Note for whoever builds it: the garage precedent's UI half is **documentation, not code** -
`GarageController`'s KDoc and `GarageOpener.kt:70` both reference a `ui.GarageSheet` that does not
exist. Do not copy from it. Copy the dialog shape from `CompanionRows.kt`.

### D6. No maintenance-log entry. (Stark)

Nothing is written to `service_records` or `maintenance_items`. Clearing codes is not work
performed on the car. The governing precedent is `AdvisorProposalExecutor`, whose KDoc
(`:198-205`) keeps `lastDoneMileage`/`lastDoneDate`/`neverDone` off its allowlist because they are
**claims about work actually performed**. A code clear is a diagnostic act, and
`code_clear_events` is its record.

### D7. Fleet must subtract cleared codes, by union rule. (Stark)

D3 buys nothing unless the UI stops asserting erased codes are stored. "STORED CODES" renders:

> `(code_events with timestamp > the latest CLEARED clear-event for this vehicle)` **union**
> `(that clear-event's codesAfterJson)`

The union term is load-bearing: a `RETURNED` code's original `code_event` predates the clear and a
naive timestamp filter would hide a fault that is demonstrably still live. Deriving it from
`codesAfterJson` needs no double-write and no reliance on the health-monitor poll re-observing it.

The block also gains a **`CLEARED <date>`** line when a clear-event exists, so an absence is
explained rather than mysterious. `RETURNED` and `UNVERIFIED` clears do **not** filter anything.

### D8. Observability: three channels, because logcat is not one. (Stark)

`MidnightEvents` is `Log.d`-only with no persistence (`MidnightEvents.kt:5-10`), and the device
filters this app's logcat (`CarProbeLog.kt:21-30`, `ObdBluetoothManager.kt:785-788`). So:

1. **`MidnightEvents.dtcCleared(outcome, before, after)`** - new breadcrumb beside the existing
   `obdPidSilence`/`obdReinit` hooks (`:62`, `:68`).
2. **A `CarProbeLog` entry**, because that is the existing "must be readable ON THE SCREEN" channel.
3. **The `code_clear_events` row**, which is the only one that survives process death.

The turn is **not** added to `EPISODIC_EXCLUDED_TOOLS` (`LiveToolbox.kt:1531`). A clear is a
falsifiable fact about the car and belongs in episodic memory.

### D9. The register. (Stark, subject to Kevin's read-aloud)

Alfred/JARVIS band per CLAUDE.md §1: competent, dry, useful. Estimates labelled per §4 rule 5.

**Confirm prompt (call 1)** - codes named, one warning clause, engine clause only when RPM > 0:

> "Two stored: P0420 and P0128. Clearing wipes both, wipes the freeze frame, and resets the
> readiness monitors, so the car will fail an emissions test until it has driven enough to reset
> them. [Engine is running - some ECUs refuse a clear while running.] Do you want me to clear?"

**Outcome lines:**

| Outcome | Spoken |
|---|---|
| `NOTHING_TO_CLEAR` | "Nothing stored. Nothing to clear." |
| `CLEARED` | "Cleared. Nothing stored now. That means the erase took, not that the fault is gone - a live fault will come back after a drive cycle." |
| `RETURNED` | "Sent the clear. P0420 came straight back. That fault is active, not stored." |
| `UNVERIFIED` | "I sent the clear, but the car stopped answering, so I do not know whether it took." |
| `REFUSED` | "The car is not answering. I have not sent anything." |

`CLEARED`'s second sentence is the D1 anti-overclaim and is not optional.

### D10. Tool budget: +1, and it must be its own declaration. (Stark)

The map's standing preference makes every new domain argue its tool-token cost. `clear_codes` adds
one declaration to a surface that already carries seven DTC-related tools (`get_vehicle_data`,
`diagnose_codes`, `get_codes`, `get_code_history`, `check_readiness`, `get_health`,
`triage_symptom`).

Folding it into `get_codes` as an action parameter was considered and rejected: **a destructive
write hidden inside a read tool's declaration cannot carry its own warning**, and the `confirmed`
param would then apply to a tool that is usually harmless. The garage and grocery precedents both
put the confirm on a dedicated declaration.

---

## Build spec

Order matters - the migration lands before anything reads the table.

1. **`data/local/CodeClearEvent.kt`** + **`CodeClearEventDao.kt`** (`insert`, `getAll(vehicleId)`,
   `getLatestCleared(vehicleId)`). Register the entity in `CarDatabase.kt`, bump `version = 22`,
   add `MIGRATION_21_22` with **kapt-generated SQL copied verbatim**, commit the schema JSON.
   Wire into `sync/` as `Mode.UNION`.
2. **`vehicle/DtcClearController.kt`** - pure, no Context. One entry point returning a result
   carrying `ClearOutcome`, the before/after code sets, and the spoken line. Holds the confirm
   gate (`if (!confirmed) return ...prompt...`), the five-outcome logic, and the prompt/dialog
   wording generator shared by both surfaces.
3. **`ObdBluetoothManager.clearDtcCodes(): String`** - `sendCommand("04")` with the PID-silence
   counter opted out. Returns the raw response for `ackRaw`. Implemented **once**; `BleTransport`
   and `RfcommTransport` share the `ObdTransport` stream seam (`ObdTransport.kt:25-33`).
4. **Voice tool `clear_codes`** in `LiveToolbox` - declaration with the required `confirmed` param
   in the `activate_garage` shape, dispatch as a thin wrapper over the controller, added to
   `CATEGORY_A_TOOLS`.
5. **Fleet UI** - action on the STORED CODES block, `AlertDialog` in the `CompanionRows` shape,
   body text from the controller. Apply the D7 union rule and the `CLEARED <date>` line.
6. **`MidnightEvents.dtcCleared`** + the `CarProbeLog` entry.

### Verification (CLAUDE.md §8 L11 - these are gates, not notes)

- [ ] Unit tests over a fake `ObdTransport` covering **all five outcomes** plus the
      `confirmed=false` gate. Modelled on the existing `GarageController` tests.
- [ ] A test asserting **no `service_records` / `maintenance_items` row** is written (D6).
- [ ] A test asserting the D7 union rule shows a `RETURNED` code and hides a `CLEARED` one.
- [ ] Migration test v21 -> v22. **Expect it to compile and never run** - `connectedAndroidTest`
      uninstalls the app and would take Kevin's real data (`MEMORY.md`). Record it as such; the
      on-device evidence is the database opening after install.
- [ ] `compileDebugKotlin -Pnokey` and `testDebugUnitTest` green.
- [ ] **On-device, on a car with a real stored code**: the confirm prompt names the actual codes;
      a clear produces a `code_clear_events` row; STORED CODES stops showing the cleared code and
      shows `CLEARED <date>`. Verify the install by sha256, never by "Success".
- [ ] **`UNVERIFIED` and `REFUSED` cannot be produced on demand.** Force them by killing the OBD
      link mid-transaction, or accept them as reasoned-only and say so in the build report.
