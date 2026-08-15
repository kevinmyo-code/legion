# The Jeep row lost its identity and its odometer, in normal use

Type: grilling
Status: open

## Question

Surfaced by ticket 01, which pulled the real database. The active vehicle row
`12:34:56:11:22:33` today reads:

| field | value |
|---|---|
| `name` | `1998 Jeep Cherokee` |
| `make` / `model` / `year` | **empty / empty / 0** |
| `confirmed` | 1 |
| `onboarded` | **0** |
| `odometerBaseline` | **0** |
| `odometerBaselineAt` | **never** |
| `tripMilesSinceBaseline` | **0.0** |

**It did not always read that way.**

- On **2026-07-18 10:24:12** it had a year, make and model. It must have:
  `applyServiceIntervals` seeded ten maintenance items from a prompt built out of those three
  fields, and `onboardPendingVehicles` (`VehicleController.kt:653-659`) skips a vehicle with a
  blank make or model. Those ten rows are still on disk with that timestamp.
- On **2026-08-12 15:50:02** it had an odometer of about 118,374. It must have: `logServiceDirect`
  (`:179`) derives a `ServiceRecord`'s mileage from `currentMileage(vehicle)`, and the record it
  wrote that second says 118,374.

So five fields were populated and are now empty, and `onboarded` went from true back to false.

**Everything this map ships is built on that row.** A fix that restores the identity and the
odometer is worthless if the same thing happens again next month.

## What was already ruled out (ticket 01, by reading, not assuming)

| Suspect | Verdict |
|---|---|
| Migrations 16→17, 17→18, 18→19 (all ran 2026-08-13) | **Not it.** All three touch `ledger_transactions` and `category_rules` only. Neither mentions `vehicles` |
| `correctVehicle` - the rename, stamped 2026-08-13 14:37:54 | **Not it.** Builds `existing.copy(...)` and coalesces every field (`:434-442`). Preserves the odometer, cannot blank make/model. It could only have renamed a row that was **already** empty |
| `registerDirect` | **Not it.** Preserves the odometer explicitly (`:98-100`) and rejects a blank make or model at the door (`:86`) |
| `setOdometer` | Cannot produce this. It sets `odometerBaselineAt` alongside the baseline; here the baseline is 0 and `odometerBaselineAt` is **never**, a combination it never writes |

## What has to be decided

1. **Find the writer.** Enumerate every path that writes a `vehicles` row and check each against
   the observed end state - specifically, which can produce `make=''`, `model=''`, `year=0` while
   leaving `name` and `confirmed` intact. Candidates not yet audited: `sync/SyncEngine` (LWW merge
   on `vehicles`, `:183`), `data/MidnightImport` (`:190`, imports the table column-for-column - and
   ticket 01 found it **duplicated 5,242 obd_samples rows** between two vehicle ids, so it has
   demonstrably run and demonstrably made a mess), `DriveReassigner`, `ObdDeviceRegistry`, and
   `seedVehicle` (`:1036-1044`) if it can ever fire against an existing MAC.
   **`sync/` has never executed** per MEMORY.md, which if true removes the strongest suspect - so
   confirm that claim rather than inheriting it.
2. **`registerDirect`'s silent field loss, which is real regardless of whether it caused this.**
   It constructs a fresh `Vehicle(...)` rather than copying, so it drops `voiceName`,
   `personaTraits`, `trim`, `archived` and `lastOdometerPromptAt` to their defaults every time it
   runs. **Nobody has ticketed that.** Same class as the bug being hunted here, and cheaper to fix
   than to find.
3. **Whether the row can be repaired from data already on disk.** `vehicle_specs` holds the decoded
   VIN `1FAKEVIN000000001`: 6-cylinder, 4.0L in-line, FCA, Toledo, SUV, decoded 2026-07-26. That is
   enough to restore make and model. The **year** is in the VIN's 10th character (`W`) and is not
   stored as a field. The odometer is recoverable only as an approximation from the service records
   (118,374 on 2026-08-12) - **and an approximation must not be written as if it were a reading.**
   §4 rule 5 applies: a restored odometer Kevin did not state is an estimate.
4. **Why the decode never wrote back at all.** `vehicle_specs` and `vehicles` have disagreed for
   three weeks with nothing noticing. Is the write-back missing by design (specs are a separate
   concern) or by omission? If by design, **something still has to reconcile them**, because
   `check_recalls` and every label surface read `vehicles` and the truth is in `vehicle_specs`.
5. **How this becomes noticeable.** A car with an empty make and model is a detectable state.
   Today it renders as `THIS CAR` and nothing else. Should it be surfaced - the way ledger surfaces
   a quarantine - rather than silently degrading every dependent feature?

## Note on scope

This ticket was not on the original chart. It exists because ticket 01 looked at the data, which is
the fourth time on this repo that looking at the data has found what the test suite could not
(L15, and the 2026-08-13 ledger session's four bugs). **It blocks nothing formally**, but ticket
04's identity fix and ticket 12's recall button are both papering over whatever this is until it
is understood.

## Verification

Whatever writer is identified, reproduce it on a **copy** of the database, never on the phone.
CLAUDE.md §5's discipline for migrations applies to this diagnosis too: prove it against a copy of
Kevin's real data first.
