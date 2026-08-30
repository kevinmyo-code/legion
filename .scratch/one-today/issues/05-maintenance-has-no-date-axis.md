---
type: decision
status: open
blocked_by: []
map: one-today
---

# MAINTENANCE 0 DUE is computed against nothing

Found in the 2026-08-30 audit, from the real device snapshot.

`maintenance_items`: **54 rows. 48 carry a mile interval, 35 carry a month interval, and ZERO carry a
`lastDoneDate`.** None is flagged `neverDone` either.

`VehicleController.isDue` is dual-axis - miles **or** months. With no `lastDoneDate` on any row, **the
month axis has no anchor to measure from on any item.** So the 35 time-based intervals contribute
nothing, and FLEET's "0 DUE" is the mileage axis alone.

**That number may not mean what it says.** It reads as "nothing needs doing" and actually means
"nothing has tripped the one axis that still works."

## What needs deciding

1. **Is the mileage axis anchored?** `lastDoneMileage` was not checked in the audit. If it is also
   empty, "0 DUE" means nothing at all rather than half of something. **Check this first** - it
   decides whether this ticket is a wording fix or a data-recovery job.
2. **Where would a `lastDoneDate` come from?** `service_records` has 6 rows and is the obvious
   source - a service record IS a thing having been done on a date. Whether they can be matched to
   maintenance items is unknown and should be checked before designing anything.
3. **What should the tile say when it cannot tell?** CLAUDE.md's own posture answers this: unreadable
   and empty are different sentences. A tile that cannot compute due-ness should say so, not print a
   confident zero. That is the same rule that made undated note items render as "(showing tomorrow,
   no date set)" instead of silently appearing.

## Why it belongs to this map

If HOME is going to aggregate "what do I need to do today" across aspects, maintenance is one of the
aspects - and it would contribute a confident, possibly meaningless zero to the union. **Better to
know that before it is one line in a summary Kevin trusts.**

## Also worth noting

`maintenance_items`' 54 rows are split across FOUR `vehicleId`s, including **`default` (16 rows)** -
one of the two year-0 placeholder vehicles that the fleet cutover refuses to export and that halted
the OBD upload. Sixteen maintenance items hang off a car that does not really exist.
