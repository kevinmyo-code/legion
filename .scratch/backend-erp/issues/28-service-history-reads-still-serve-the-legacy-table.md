---
type: build
status: open
blocked_by: []
map: backend-erp
---

# Service history reads never moved to the replica

**Left behind by ticket 26 step 2, 2026-08-29, deliberately and with the reasoning recorded rather
than as an oversight.**

`service_history` writes are cut over: a configured install pushes to Supabase and the replica.
**Reads still serve the legacy `service_records` table unconditionally.**

## Why it was not done in the same step

`ServiceRecord.id` is a load-bearing local surrogate - `VehicleController.editServiceRecordDirect`
and `deleteServiceRecordDirect` address rows by it, and `MaintenanceSchedule`'s anchor derivation
groups by those rows. `ServiceHistoryReplica` is refilled **wholesale** by `FleetReconcile`, so
serving reads from it would hand callers ids that change under them on every reconcile.

That is `b17bc88` precisely - a wholesale replica refresh reminting local ids that turned out to be
alarm request codes and soft foreign keys. `Vehicle` avoided this only because it has no numeric id
at all; everything keys on `obdMac`.

## What "done" looks like

A **merge**, not a repoint: a replica row with no local counterpart is materialised into
`service_records` with a fresh local id, and thereafter matched by `serverId`. Reads keep serving the
local table, which keeps every id stable, while the replica becomes the channel that fills it.

That is the same shape `EventsReconcile` already uses for Notes - carry the id, never remint it - and
that path is worth reading before designing this one.

## What this means until then

A service record created on the PC surface **will not appear on the phone**, even though the phone
uploads its own. Fleet is half-symmetric: the phone tells the server, the server does not yet tell
the phone.

**Not a data-loss risk** - nothing is dropped, the row is on the server and reachable from the web
app. It is a visibility gap, and it should be closed before anyone relies on entering service records
from the laptop.

## Related

Every other fleet table with a numeric-id-keyed legacy table faces this same question when its turn
comes. `FleetEngineStore`'s class doc is the authoritative record of the distinction; read it before
repointing any of them.
