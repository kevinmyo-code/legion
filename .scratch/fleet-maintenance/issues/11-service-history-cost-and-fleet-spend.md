# Service history, cost capture, and fleet spend

Type: grilling
Status: open
Blocked by: 09

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
