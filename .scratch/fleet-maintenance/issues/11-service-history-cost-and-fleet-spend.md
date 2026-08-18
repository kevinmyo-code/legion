---
map: fleet-maintenance
ticket: 11
title: "Service history, cost capture, and fleet spend"
type: grilling
status: resolved
status-detail: 2026-08-15
blockers: ["09"]
blocked-by: ["[[09-the-maintenance-surface-rebuilt]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Service history, cost capture, and fleet spend

## Question

The maintenance drilldown prints `"2 service records on file, no screen yet"`
(`ui/fleet/FleetDrilldowns.kt:142-148`). Kevin: build it.

Two facts make this bigger than a list screen.

**`ServiceRecordDao`'s two `Flow` accessors have zero collectors anywhere in the app.**
`getAllRecords` and `getRecordsForVehicle` (`:17-21`) were written for a screen that was never
built. The DAO also has **no update and no delete** - only `insert` (`:14-15`).

**`ServiceRecord.cost` has no writer at all.** No code path passes it. It is read by
`BuildSheetController.totalCost` (`:22`) and formatted in `CarToolbelt.serviceHistory` (`:108`),
both of which are therefore reporting on a column that is null on every row in existence. Kevin
asked for fleet spend on the fleet screen; **there is currently nothing to add up.**

## What has to be decided

1. **Where cost gets captured.** The voice tool `log_service` (`LiveToolbox.kt:809`) does not take
   one. A UI log form can ask. Retro-entry for the two existing records needs an edit path, which
   the DAO does not have. **Cost is `Double` dollars on this entity** (`ServiceRecord.kt:17`), which
   contradicts CLAUDE.md §4 rule 3 (money is `Long` cents, never `Double`) - decide whether to
   migrate the column or to scope the exception explicitly. Fleet money has never been summed
   before, so this is the moment it starts mattering.
2. **Edit and delete on service records.** Kevin will mistype a mileage. `ServiceRecordDao` has
   neither. Unlike `maintenance_items`, this table syncs on a **portable `syncId`**
   (`SyncEngine.kt:175`, UNION), not a natural PK - so its delete story is different from ticket
   07's and has to be reasoned about separately rather than by analogy.
3. **What the history screen shows.** Flat reverse-chronological list, grouped by service type, or
   grouped by year? With two records this is trivially easy and with two hundred it is not - design
   for the second. Per-item history from ticket 09 question 6 may make this screen a filter over
   the same data rather than a separate list.
4. **What "fleet spend" actually is.** Kevin chose fleet-local maths on the fleet screen. Candidates:
   total cost of ownership, cost per mile, spend by service type, spend per year, cost since the
   last odometer baseline. **Cost per mile is the interesting one** and it is the one that depends
   on ticket 10's odometer being trustworthy - say so rather than quietly dividing by an estimate.
5. **Where it renders.** A tile on FLEET, a panel on the history screen, or a drilldown.
   `quant-viz` established that **every tab face carries inline viz** and Kevin's standing
   instruction is *"im not gonna read numbers, it has to be glanceable"* - so a spend figure
   probably arrives with a chart, and `dataviz` plus mission-control's chart kit govern it.
6. **Records with no cost.** Both existing ones. A total that silently omits them is a lie by
   omission of exactly the shape CLAUDE.md §4 rule 6 names. **How many rows the figure covers has
   to be said in words**, the way ledger's spend disclosure does it.
7. **`BuildSheetController.totalCost` today.** It sums a column that is null everywhere. Whatever
   it currently reports, it is not what it claims. Check what it renders and fix or scope it.

## Watch for

The build-sheet ghost line (`"N build sheet entries on file, no screen yet"`) sits directly beside
the service-record one and is the same shape of gap. **It is out of scope for this map** -
modifications, not maintenance - but ticket 09 question 8 decides whether the dead line stays on
screen.

## Verification

On the device with Kevin's two real records: the screen lists both, a cost added to one persists
across a force-stop, and the fleet spend figure states how many records it covers. Pull the DB.

---

## Answer (2026-08-15)

### `cost` migrates to `Long` cents

**Decision (Kevin).** `ServiceRecord.cost: Double?` becomes `costCents: Long?`. CLAUDE.md §4 rule 3
without an exception.

**The migration is free, and that is the argument for doing it now rather than later.** `cost` is
**null on both of Kevin's records** and has **no writer anywhere in the app** (ticket 01), so there
is no data to convert and no rounding to reason about. Every later moment is more expensive than
this one.

This is also the moment it starts mattering: fleet money has **never been summed** before, and the
four figures below are the first arithmetic ever done on it. `BuildEntry.cost` stays `Double` and is
now **the odd one out** - note it on that entity so the inconsistency is deliberate and visible,
not a second convention nobody chose.

Room: drop-and-recreate the column, or add `costCents` and drop `cost`. Additive-plus-drop is not
additive - so this is the one place on this map where §5's "additive only" needs a stated exception,
justified by the column being provably empty. **Prove that against a copy of Kevin's real database
first** (`SELECT COUNT(*) FROM service_records WHERE cost IS NOT NULL` must be 0), then migrate.

### Capture, edit and delete

- **Capture at log time.** The UI log-a-service form takes a cost. The `log_service` voice tool
  gains an optional `cost` argument (it has none today, `LiveToolbox.kt:809-817`).
- **Edit**, so Kevin's two existing records can get their costs filled in retroactively, and so a
  mistyped mileage is fixable. `ServiceRecordDao` has `insert` only - no update, no delete.
- **Delete**, with the tombstone shape from ticket 07.

**And a caveat that must be stated on the ticket and in the code, because it is not obvious:**

`service_records` syncs **UNION on a portable `syncId`** (`SyncEngine.kt:175`), not LWW. The sync
doc is explicit that the tombstone pattern **cannot work under UNION** (`:222-226`): UNION never
updates an existing local row, so a `deleted = 1` would never propagate, and the other device would
simply keep its copy. **A service-record delete is therefore LOCAL ONLY.**

That is acceptable for a two-phone solo app **only because it is stated rather than discovered**,
which is the same standard ticket 07's question 3 set. Do not describe the delete as if it were
global. If sync ever runs, a deleted record can reappear from the other phone.

### Fleet spend: all four figures, and two of them need caveats

| Figure | Caveat |
|---|---|
| **Total spent, all time** | must state **how many records it covers** - a total that silently omits cost-less records is a lie by omission of exactly the §4 rule 6 shape. Both of Kevin's records are cost-less today, so the honest current answer is "no costs logged yet", not "$0" |
| **Cost per mile** | divides by an odometer that is an **estimate** (ticket 10), so it inherits that caveat and says so. On Kevin's Jeep the odometer is currently **0**, so this figure must refuse rather than divide |
| **Spend by service type** | groups on `serviceName`, so it inherits ticket 07's duplicate problem - `Air Filter` and `Air Filter Replacement` would split one category in two. Group on the **canonicalised** name, as a comparator, never by rewriting stored data |
| **Spend per year** | needs several years to say anything; renders a worded empty state until then |

Rendering: `quant-viz` established that every tab face carries inline viz and Kevin's standing
instruction is *"im not gonna read numbers, it has to be glanceable"* - so spend arrives with a
chart, governed by `dataviz` and mission-control's chart kit. **Money stays mono, right-aligned.**

### `BuildSheetController.totalCost` is currently reporting on nothing

`:22` sums a column that is null on every row in existence. Whatever it renders today is not what it
claims. Fix it with the migration or scope it - but do not leave it summing a field that changed
type underneath it.

### Where the history screen lives

Ticket 09 put per-item history on the **item detail screen**. The full service history is the same
data unfiltered, reached from the full-schedule screen. **One list implementation, two entry
points** - not two screens that can drift.

Design for two hundred records, not two: reverse-chronological, grouped by year.

### Verification

1. On the device with Kevin's two real records: both listed, a cost added to one **persists across a
   force-stop**, pull the database and confirm `costCents` is an integer count of cents.
2. The total **states how many records it covers**.
3. Cost per mile **refuses** while the odometer is 0, in words, rather than dividing by zero or
   rendering a nonsense figure.
4. Prove the migration against a **copy** of the real database first; confirm the schema JSON.
5. **The delete's local-only nature cannot be tested** - `sync/` has never executed. Say so.

### Assumptions ledger

- `traced`: `cost` being `Double?` with no writer; `ServiceRecordDao`'s insert-only surface;
  `SyncEngine.kt:175`'s UNION spec and `:222-226`'s statement that tombstones cannot work under it;
  `BuildSheetController.totalCost` summing it; `log_service`'s parameter list.
- `on-device`: both records have `cost` null.
- `reasoned`: that the migration is risk-free because the column is empty. Verify with the count
  query before relying on it.
- **Not built.**
