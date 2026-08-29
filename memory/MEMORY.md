# MEMORY.md

Dashboard for LEGION. **MEMORY.md wins for state, CLAUDE.md wins for rules.** Depth lives in the
library. Under 80 lines. MIDNIGHT_AI: see CLAUDE.md §1.

**Cut back to its cap on 2026-08-28**, from 960 lines. Nothing was lost or edited: the session
narrative moved verbatim to `library/session-2026-08-backend-erp.md`, and the blockers /
never-exercised list / device traps to `library/standing-caveats-2026-08.md`. Read those two before
trusting a green suite.

## NOW - 2026-08-28: the backend-ERP map is CLOSED. Every ticket on it is resolved or built.

- **Phases 0-4 are done in code.** Places, pantry, notes, dates and ledger are all off the generic
  engine and on typed tables. Fleet is a **projection**, not a cutover (ticket 14).
- **The engine SURVIVES, scoped** (ticket 18): `create_aspect` + the generated UI + the widget pager
  still need it. `engine/EngineBoundaryTest` fails the build if a built-in aspect reaches back in.
- **Phase 6 is smaller than it was written**: `engine/` is not deleted, only genuinely dead tables.
- **One ticket left open on purpose: 19, re-ingest the historical statements.** It waits on a human
  with the phone, not on code.

## THE GOAL, restated by Kevin 2026-08-28 (late), and it narrows the map

*"we dont need the old data to port over fully. we control the backend now. we just connect new data
from the phone, keep what we have, if we cant recover or migrate fully just kill it. the data is not
important. whats important is we set up the backend properly for new data from the phone or any
other surface to be ingested."*

**Historical migration is no longer a goal. A correct ingestion path for NEW data is.** Ticket 19
(re-ingest historical statements) is KILLED on that basis; the pre-cutover `DETERMINISTIC` rows stay
on the phone, unuploaded, neither deleted nor relabelled. The server's history begins with the first
record written under the new path.

Consequence worth carrying: ticket 12's gate on retiring the deterministic parsers is RELEASED
(nothing is re-reading those statements by any route). C4's own gate - the three-anchor CSV path
must work first - still stands.

## Blocking, and they are Kevin's, not code's
2. ~~Exercise a real `DatabaseSnapshot` RESTORE on the A25.~~ **DONE 2026-08-28.** Full round trip,
   all 65 tables back to their pre-drill counts. It found a real defect on the way: `SCHEMA_VERSION`
   was stale at 47 against `@Database(version=)` 49, which disabled restore on every backup the
   running app produced. Phase-6 mirror deletion is unblocked. Detail in ticket 04.
   **And the Kwin-laptop-only rule was FALSE** - both machines carry the same debug key, proven by
   comparing the installed APK's signer against this machine's keystore. `adb install -r` keeps the
   data; never uninstall.
3. **Apply the unapplied SQL migrations** and confirm from `pg_class`/`pg_policy`, never the editor's
   success panel. In the dashboard, **"Run without RLS" is the correct button** - the migrations
   enable RLS themselves inside an `execute format` the analyzer cannot see.
4. **51 rows on the live project are still falsely marked missed** (2026-08-27, mine). The guard is
   now landed - `events.kind` plus deletions travelling - so clearing them is safe, but clearing
   them is Kevin's call against his own data.

## Owed on the phone - nothing below has run on hardware

- Every phase-4 repoint is compile-and-suite green only. **The unconfigured path is clone-and-run,
  and no device here has run it.**
- `runFleet` has never been tapped. The fleet projection is unproven. Its server constraint IS
  applied now (`20260828000100`, verified 11/11 check constraints on `public.events`), and there are
  **14 active car tasks**, so the wave will do real work rather than no-op.
- A reminder set BEFORE the notes cutover still firing AFTER it. `AlarmScheduler` has zero tests
  repo-wide and the `PendingIntent` request-code contract is exercised by nothing.
- `CarDatabaseMigration40To41Test` is written and has never been RUN.
- The configured Notes path has never touched a real Supabase project.
- **No alarm has ever fired. `sync/` has never executed. No Compose preview has ever rendered.**
  Full list: `library/standing-caveats-2026-08.md`.

## Rules that changed recently, so nobody re-derives them

- **CLAUDE.md §4 rule 8 is new (2026-08-28):** persist the anchors, not just the verdict. A gate that
  passes in memory and discards its inputs leaves rows nobody can re-verify. Found twice - three
  pantry receipts, then the ledger's whole verified history.
- **A DETERMINISTIC statement qualifies on TWO read anchors plus the balance delta** (ticket 12). No
  bank prints a combined total, so the third does not exist to be read. **The LLM CSV path still
  requires all three** - the amendment is scoped to deterministic extraction and nothing else.
- **Recaps stay on the phone** (ticket 10). Moving them server-side owes a shared corpus, and the
  laptop surface that would consume them does not exist yet.
- **Fleet keeps its `SyncEngine` registry entries.** Drive is still how fleet syncs between two
  phones; Postgres is write-only from the phone, so there is nothing for the two to disagree about.

## Standing queue behind this map

Widget tap-through (widgets still do not navigate), legacy table drops (WAIT until the backend arc
proves out on hardware), deferred fleet entities, semantic recall (`.scratch/ai-craft/02`), the
wiki-notes second brain Kevin asked for (`research/wiki-notes-second-brain.md` - do not lose it),
and the TRAINING revamp, charted week-first and not started.

## Library + how to update this file

`memory/library/` (catalog: `INDEX.md`). Never bulk-read shelves; dispatch the librarian, then
**verify what it writes** - it has invented content before. **Most shelves are FROZEN Midnight AI
history.** LIVE: `decisions.md`, `lessons.md`, `playbook-coding.md` (partly),
`session-2026-08-backend-erp.md`, `standing-caveats-2026-08.md`. CLAUDE.md §11.
- Under 80 lines. One-liners; narratives go to the library, then refresh Blocking / Owed / Queue.
  It reached 960 lines once by appending a session section per day - append to the library instead.
- A decision changing a CLAUDE.md rule is filed to `library/decisions.md` AND applied to CLAUDE.md
  in the same commit; a lesson graduates the same way (L14 -> §4 rule 6, ticket 12 -> §4 rule 8).
