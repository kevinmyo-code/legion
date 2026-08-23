---
map: aspect-engine
ticket: "13"
title: "Two-phone sync under the engine"
type: grilling
status: resolved
status-detail: "Resolved 2026-08-23: the xlsx files ARE the sync channel, row-merge mandatory."
blockers: ["12"]
blocked-by: ["[[12-xlsx-mirror-import-gate]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Two-phone sync under the engine

## Question

Today `sync/` pushes app state to Drive `appDataFolder`, and CLAUDE.md sec 2 carries the open
finding: Drive has no compare-and-swap, so shared-file last-write-wins loses rows silently. The
engine changes the terrain - one generic record shape, and an xlsx mirror already living in a
visible Drive folder. Decide:

1. Does the existing appDataFolder SyncEngine survive, or does the visible folder become the sync
   channel too - two phones both mirroring to and importing from the same folder?
2. If the folder is the channel: the mirror is whole-file, which is exactly the last-write-wins
   trap. Does sync need an append-only journal file alongside the xlsx (journal = truth for sync,
   xlsx = view for humans), replayed to rebuild? This was the standing recommendation before the
   engine and it fits better now - one record shape means one journal format.
3. Conflict semantics for the same record edited on both phones (updatedAt wins? field-level
   merge? surface it?). Two adults, one household - how much machinery does reality need?
4. What syncs beyond records: aspect definitions, widget layouts (per-device or shared?), the
   date database, companion profiles (already have CompanionSync).
5. Offline is dropped (charter decision 4), which simplifies: is sync-on-write acceptable now?

## Answer

Resolved 2026-08-23 (Kevin, batched grilling). **Kevin chose: the xlsx files ARE the sync
channel.** Both phones export to and import from the same visible Drive folder. The journal
option and the appDataFolder channel were both declined.

**Binding condition that defuses the last-write-wins trap** (stated to Kevin with the decision):
import is always a **row-level merge keyed by record id plus updatedAt** - latest edit of a row
wins, never whole-file replace. Residual loss window is the same row edited on both phones
between syncs, accepted for a two-adult household. The read-back hash verify from ticket 12
guards the file layer underneath.

Details: aspect definitions sync through a definitions sheet in each workbook under the same
merge rule; widget layouts deliberately do not sync (ticket 08); the appDataFolder SyncEngine
retires for record data, with CompanionSync's profile handling reviewed at migration time;
offline is out of scope (charter decision 4), so import runs on app foreground and after export.

Build work: [Build the mirror and sync](20-build-mirror-sync.md).
