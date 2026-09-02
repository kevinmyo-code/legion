---
map: live-sync
title: "Map: The phone runs on the backend, instead of visiting it"
charted: 2026-09-02
charted-by: "Kevin + Opus"
effort: "`.scratch/live-sync/`"
tickets: 5
open: 2
status: open
tags: [map]
---
# Map: The phone runs on the backend, instead of visiting it

## Destination

**Kevin, 2026-09-02:** *"can we have the app just sync to the backend live instead or something
automatic"* and then *"build the sync end to end"*.

An app that HAS a backend visits it when told. An app that RUNS ON one is never meaningfully out of
date. LEGION was the first; this map is the second.

## What was actually wrong, found by measuring rather than reading

The app called seven things "reconcile" and none of them was a sync. All seven had exactly one
caller apiece - a row on a Settings screen - and **not one of them compared a timestamp.**
`updated_at_ms` was set faithfully on every local write and had never once been read.

The cost was not theoretical. On 2026-09-02 the server held **120 coursework rows the phone had
never displayed** - `COSC 3334 · Homework 1-6`, both midterms, `COSC 3318 · FINAL EXAM`,
`COSC 4305 · Report 1/7` through `7/7`, the first due in two days. Kevin had been reading a phone
that showed six reminders, of which none was schoolwork.

`EventsReconcile` did not merely fail to fetch them. It **wiped** every local `kind='reminder'` row
(`DELETE FROM events WHERE kind = :kind`, no filter), refilled from the server, and **withheld** any
row it could not attribute to a still-live engine record. Withheld rows are not shown as
unconfirmed; they are absent. So the data existed on both machines and was visible on neither.

**The general shape, worth carrying to the other aspects:** a sync that resolves uncertainty by
removal will always look like it is working. Nothing errors. The logs are clean. The absence is the
bug, and absence is the one thing a passing test does not report.

## The rulings this map established

1. **A pull MERGES. It never wipes a table.** Insert what is missing, resolve collisions by
   last-write-wins on `updatedAtMs`, honour tombstones - and **leave a local row the server lacks
   entirely alone.** Absence from the server is not evidence of deletion. That single rule is the
   difference between the old behaviour and the new one.
2. **Drain before pull.** A local change must reach the server before the pull can weigh
   last-write-wins against it. Reversed, the user's own edit loses to the copy it was meant to
   replace. Commented at both the call site and the drain, because it is precisely the kind of
   ordering someone tidies into the wrong order later.
3. **A realtime event is a TRIGGER, not a data source.** It fires the same `EventsSync.pull`
   everything else uses. One merge implementation, one set of tests, one thing that can be wrong.
4. **A failed write is queued, never dropped**, and the local write still lands so the UI stays
   honest. A rejected write stops after a bounded number of attempts rather than retrying forever -
   a poison row that retries on every app open is worse than one that halts and says so.
5. **Do not trust a client-minted id.** `LiveToolbox.addAppointment` minted a random UUID as
   `serverId` for rows that had never touched the server. Matching now prefers the `guid`, and a new
   row carries a NULL `serverId` until a real round trip earns it.

## Tickets

| | State |
|---|---|
| 01 A pull that merges, and runs unasked | **resolved** 2026-09-02, `2daeae4`. Verified on the phone: reminders 6 -> 149, all 7 internship reports present, `Report 1/7` at Fri Sep 4 23:59 matching Canvas exactly |
| 02 Write-through for `kind=EVENT`, and a durable outbox | **built** `9fc7141`, suite green. Room v59. NOT yet run on hardware |
| 03 Realtime, and tombstones that propagate | in flight |
| 04 Retire `EventsReconcile` | **resolved** 2026-09-02. Deleted along with its test, its Settings row, and the two wipe-only DAO methods it alone called. Also reversed appointment rename/delete off local-only (see `memory/library/decisions.md`'s 2026-09-02 entry) - both now route through a write-through-plus-outbox path, delete produces a tombstone. `compileDebugKotlin`/`testDebugUnitTest` green, 2870 tests / 0 failures |
| 05 The same treatment for the other six aspects | open |

## Known, and deliberately not fixed here

- ~~Renaming or deleting an appointment is still local-only, even on a configured install.~~
  **REVERSED 2026-09-02 (ticket 04's own follow-up, Kevin authorised) - see
  `memory/library/decisions.md`'s 2026-09-02 entry.** Both now route through
  `EventsAppointmentWriter.updateEvent`/`.deleteEvent`.
- **`EngineNotesRetirementCopy` still mints fake `serverId`s.** It is a one-time batch copier, not a
  live path, so it was named rather than rewritten.
- **The other six reconciles are untouched.** Places, pantry, ledger, fleet, OBD samples and
  conversation audit all still have the one-button shape. Fleet and places already write through
  live via their controllers, so their gap is the pull, exactly as events' was. Ticket 05.
- **`obd_samples` is registered in TWO sync channels at once** - the legacy Drive-JSON `SyncEngine`
  registry and `ObdSampleReconcile`'s Supabase batch upload - and neither file references the other.
  Nobody has decided which wins. Found during the survey, unresolved.
