# Fleet + Telemetry surface: vehicle uplink

Type: grilling
Status: resolved
Blocked by: 01, 02

## Question

Fleet and Telemetry are two screens today; the deck fiction wants a vehicle uplink. Decide: merge
or keep split; what visualizes (maintenance timeline/due horizon, drive history, MPG trend, live
OBD readouts when connected); how staleness is worded (nothing OBD has run since the port - every
reading is a past one, `relativeAge` discipline); what the surface shows with zero OBD hardware
connected at all (the common case).

## Answer

Resolved 2026-08-08. Kevin delegated the remaining grilling tickets to the recommended defaults
("i seem to agree with ur recommendations on most of these... resolve them all with ur default
recs"); the (a)-lead recommendation below was already on the table when he did.

1. **Fleet and Telemetry MERGE into one FLEET module.** Telemetry's charts become the UPLINK
   panel's drilldown. Two screens claiming one car was head-unit heritage.
2. **UPLINK panel leads, always** - fixed position (home decision's constancy rule).
   Disconnected: `UPLINK // NO LINK` + last-known readings, every one age-worded
   (`COOLANT 194°F · 3 DAYS AGO`, the relativeAge discipline in deck voice). Connected: same
   panel goes live, same position. Reorder-on-state declined a third time.
3. **Panels, fixed order**: UPLINK (-> telemetry charts) -> MAINTENANCE (due horizon,
   overdue-first, amber advisory tags -> full schedule + service history) -> DRIVES (last drive,
   MPG sparkline -> drive history) -> CARS row (active vehicle named explicitly, tap to switch;
   places reachable from here).
4. **Staleness always worded** - a bare number never reads as live.
5. **The driving-mode offer surfaces on the UPLINK panel + as an Alfred strip prompt** when the
   link comes up (feeds ticket 11).
