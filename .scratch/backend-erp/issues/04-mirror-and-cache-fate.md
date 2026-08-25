---
map: backend-erp
ticket: "04"
title: "The xlsx mirror and the local cache in a backend world"
type: grilling
status: resolved
status-detail: "Mirror retired entirely; no reimport; full local replica; recovery moves to a SCHEDULED DatabaseSnapshot with a tested restore"
blockers: ["01"]
blocked-by: ["[[01-what-the-backend-owns]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# The xlsx mirror and the local cache in a backend world

## Question

The mirror's SYNC role dies (Supabase is the channel). Decide what survives: the audit/export
surface Kevin valued ("a relational database in sheet form that users can audit") - exported from
Supabase (server-side, or any consumer generates it), or from the phone cache as today; whether
hand-edit-and-reimport survives at all or the Supabase table editor replaces it; retention of the
Drive folder. Also the local cache's shape: a full replica of the household's records (small data,
recommend yes) vs windowed.

## Resolution (2026-08-25) - four rulings

1. **The xlsx mirror is RETIRED ENTIRELY (Kevin, 2026-08-25).** Not reduced to export-only: removed.
   The Supabase dashboard's table and SQL editors become the audit surface. Chosen over both
   surviving options, and the grounding pass turned up three facts that make it far less costly than
   the ticket's framing assumed:
   - **The import half has never round-tripped on a device.** Export to a real Drive folder WAS
     verified on the A25 on 2026-08-23 (SAF create, `rwt` rewrite, SHA-256 read-back, no quarantine
     - `.scratch/aspect-engine/issues/20-build-mirror-sync.md:7`, `:38-45`). But there is **no
     evidence anywhere that a human edited a cell in Sheets and watched it land in the app**, and
     the two-phone merge was never run. The hand-edit workflow is JVM-unit-tested only
     (`MirrorSyncMergeTest`, 12 tests; `MirrorCodecTest`, 7).
   - **There is no user-reachable mirror UI.** `MirrorSyncActivity` is `exported="false"` in the
     main manifest (`AndroidManifest.xml:226`), exported only in the debug overlay, and has **no
     `LegionRoute` entry and no button anywhere**. A release build cannot reach it except by adb.
   - **It is entirely generic-shape dependent**, so ticket 01 ruling 7 was going to collapse it
     regardless: `MirrorCodec` takes Aspect/RecordType/FieldDef/EngineRecord as its vocabulary and
     writes a `_definitions` sheet precisely because the schema lives in rows.

   Retires roughly **2,200 lines**: `engine/mirror/` (1,423) plus `ui/mirror/MirrorSyncActivity.kt`
   (186) plus `MirrorSyncMergeTest` (519) and `MirrorCodecTest` (232). **Also retires a known
   dishonesty**: "protected" columns were a header string, never a cell lock, because fastexcel
   0.19.0 exposes no per-cell lock API (`MirrorCodec.kt:61-67`).
2. **Hand-edit-and-reimport does NOT survive (Kevin, 2026-08-25).** Follows from ruling 1 but ruled
   separately because it is the load-bearing half. Every correctness risk in the mirror lived in the
   import path, and it was a genuine hole: a blank-guid row with content created a record with
   provenance `USER`, and a row carrying a foreign guid was created preserving that guid
   (`MirrorSync.kt:336`, `:355-358`) - a spreadsheet could mint records. The gate's read-only
   protection for `DETERMINISTIC`/`LLM_RECONCILED` rows was real and tested, but it protected only
   reconciled rows, not the act of creating new ones. **Nothing writes past the reconciliation gate
   through a spreadsheet any more.** Edits go through the app or through a gated RPC.
3. **The local cache is a FULL REPLICA of the household's records (Kevin, 2026-08-25).** Measured:
   **569 active engine records** (verified on device by pulling the real DB, `memory/MEMORY.md:134`),
   with a per-aspect breakdown at `:151-155`. Estimated footprint **~285 KB, worst case ~1.1 MB**
   (`reasoned` from `EngineRecord`'s column shapes; no byte count is recorded anywhere and no `.db`
   was available to measure). Against the free tier's 500 MB ceiling that is three orders of
   magnitude of headroom, so windowing would add cache-invalidation complexity to defend against a
   number that rounds to zero. Photos are excluded from that count by construction - ticket 03
   already sends them to Supabase Storage.
4. **Recovery moves to `sync/DatabaseSnapshot.kt`, SCHEDULED, with a restore actually exercised
   (Kevin, 2026-08-25).** This closes the gap ruling 1 would otherwise open, and it is the reason
   ruling 1 is safe rather than reckless.

   The feasibility research said "the xlsx mirror / export surface carries real weight as the
   recovery story" (`research/06-supabase-feasibility.md:171-173`), because the free tier has **zero
   backup retention**. **That claim was already false in practice** - it rested on an import path
   that had never round-tripped on a device (ruling 1). So the recovery gap is pre-existing, not
   created here.

   `DatabaseSnapshot` is the right home and is already built: whole-DB gzip plus a `.meta.json`
   carrying `user_version` and row count, to Drive `appDataFolder`, 3 generations, pruned only after
   the new upload is confirmed so it can never leave zero backups (`:69-81`, `:138`, `:372-380`). Its
   restore path refuses a newer schema than the running build, sniffs SQLite magic bytes, takes a
   local safety snapshot, and holds `withDatabaseLock` across the close-and-replace, rolling back on
   failure (`:90-128`). It exists because of a real incident: 2026-08-12, an older APK over schema
   v15 triggered a destructive downgrade and dropped all 42 tables (`:24-32`).

   **Two things are owed and both are binding, not optional.** First, it is **manual only** - the
   sole callers of `backupNow`/`restore` are three buttons in `ui/DriveSyncScreen.kt` (`:164`,
   `:188`, `:205`); nothing schedules it, so today's backup is as fresh as the last time Kevin
   remembered. It needs a scheduler. Second, **a restore has never been exercised on a device**
   (`NOT ESTABLISHED` - no evidence in `docs/` or `memory/`). An untested restore is not a backup,
   it is a hope, and that is exactly the mistake ruling 1 just corrected in the mirror. **Do not
   retire the mirror until the scheduled snapshot and a real restore are both done** - otherwise
   there is a window with no recovery path at all.

   **AMENDED 2026-08-25 by Kevin, later the same day, at the start of Phase 0.** The restore
   exercise is **no longer a Phase 0 gate**: the scheduler is built now and the arc proceeds
   without it, because the A25 has never been attached to the second machine (`adb devices` empty)
   and blocking the whole arc on a device that is not here was judged the wrong trade. **The
   concern was raised and overruled, which is Kevin's call to make, and it is recorded rather than
   smoothed over**: an untested restore is a hope, and this is the same shape as the mistake L36
   was written about one hour earlier.

   **What the amendment does NOT release, unless Kevin says so explicitly:** the restore exercise
   is still owed **before the mirror is deleted** in phase 6. That gate exists because deleting
   the mirror with no proven replacement leaves a window with no recovery path at all, and phase 6
   is weeks away, so nothing is blocked by keeping it. Narrow reading taken deliberately.

   Under this model Room is a full replica (ruling 3), so a snapshot of the phone DB is a snapshot
   of everything. Accepted cost, and it should be said in words on the backup screen: it captures
   the replica rather than the server, and it is only as fresh as the last scheduled run.

## Consequences for whoever builds this

**The read path is already the right shape, and that is the good news.** UI reads are
suspend-controller-calls-into-`remember` snapshots, not Room Flows: `LedgerScreen.kt:230`,
`PantryScreen.kt:81-88`, `FleetScreen.kt:358`, `WidgetPagerScreen.kt:88-102`. There are **exactly two
Room `Flow` DAO functions in the whole codebase**, both in `data/local/ServiceRecordDao.kt`.
`collectAsStateWithLifecycle` is used only for SharedPreferences- and hardware-backed state, none of
which becomes network-backed. There is **no `AppWidgetProvider`** - widgets are in-app pager only, so
there is no `onUpdate` hard deadline to design around. Making reads network-backed is structurally
undramatic.

**The real work is state vocabulary, and it is a §7 honesty problem, not a plumbing one.**
- **Ledger, pantry and fleet have no loading state.** They initialise to a default `UiState()` and
  paint it, so an empty ledger and a not-yet-loaded ledger are the same pixels. Local latency made
  that invisible; a network round trip makes it a lie - the screen would assert "no transactions"
  while it simply has not looked yet. This is the same failure shape as the calendar rule in
  CLAUDE.md §1: **unreadable and empty are different sentences.**
- **They have no error or stale state either.** Ruling 9 of ticket 01 (cache-first reads) requires a
  visible "as of" on money; there is nowhere to put one today.
- **`engine/WidgetDataSource.kt` is the worked example to copy** (`:14-24`, `:39-41`): every result
  type already distinguishes empty from not-configured from error **in words**, and
  `WidgetPagerScreen.kt:107-112` already renders an explicit LOADING state. It needs only a
  `Stale`/`Unreachable` case added. **Do not invent a second vocabulary; extend that one.**
- `reloadNonce`/`reloadKey` re-fetch an entire screen (`LedgerScreen.kt:235`, `FleetScreen.kt:365`).
  Cheap against SQLite, a full round trip against a network. Any write that bumps the key re-pulls
  everything.

**Three sync mechanisms are live simultaneously today** and ticket 05 must sequence all of them, not
just the mirror: `MirrorSync` (engine records via xlsx), `SyncEngine`/`SyncMerge` (row-level merge
over ~19 legacy tables, auto-triggered every 5 minutes from `service/AriaForegroundService.kt:216-223`
and on `MainActivity.onResume`), and `DatabaseSnapshot` (whole-DB, manual). Ticket 20 declared the
`appDataFolder` `SyncEngine` retired for record data, **but it retired zero legacy tables**, so all
three still run. Ruling 4 keeps `DatabaseSnapshot`; rulings 1 and 2 kill `MirrorSync`; **`SyncEngine`
is not ruled on by this ticket** and needs its own call in ticket 05.

**One pre-existing hole worth carrying forward:** a mirror export that fails offline quarantines the
aspect with whatever string SAF produced, and `MirrorLifecycleBinder.kt:70-71` swallows the throw to
a `Log.w`. Nothing tells the user, and the flag surfaces only on a screen nobody can navigate to.
Retiring the mirror deletes this hole rather than fixing it - but the lesson generalises to the
scheduled snapshot in ruling 4: **a background data-protection job that fails silently is worse than
one that does not exist**, because it reports safety it is not providing.
