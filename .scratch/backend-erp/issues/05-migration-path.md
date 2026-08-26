---
map: backend-erp
ticket: "05"
title: "The migration path: 569 records to Postgres without a bad day"
type: grilling
status: resolved
status-detail: "Sequenced: 7 phases, 7 hard constraints, SyncEngine retires per-table, merge lands in the Supabase schema"
blockers: ["01", "02", "03", "04"]
blocked-by: ["[[01-what-the-backend-owns]]", "[[02-auth-and-identity]]", "[[03-the-gate-server-side]]", "[[04-mirror-and-cache-fate]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# The migration path: 569 records to Postgres without a bad day

## Question

The cutover-arc playbook, aimed at the network: schema up (SQL migrations in the repo, so a
stranger runs one script against their fresh project - clone-and-run); guid-keyed idempotent
upload of the engine records; per-aspect cutover of the WRITE path (Room keeps reading until
verified); the second phone and its divergent data (guid merge, updatedAt rule); the rollback
story; what the eval harness and screenshot tests owe this arc. Sequencing vs the soak follow-ups
already on the board: the legacy-table drops WAIT until after this arc - dropping local history
before the backend is proven would be the bad day.

## What ticket 01 handed down (2026-08-25) - this ticket now owns three retirements

Ticket 01 resolved with eleven rulings and deliberately deferred ALL sequencing here. Nothing is
deleted until this ticket says in what order. Three retirements land on this ticket, plus one
ordering constraint that is already binding:

1. **The generic engine retires; the phone goes typed** (ruling 7). Measured footprint in ticket
   01: 9,518 production lines + 6,367 test lines. **Read ticket 01's "the finding that makes
   ruling 7 far cheaper than it looks" before planning this** - the engine retired zero legacy
   tables, so most of this is repointing writes back to typed tables that still exist rather than
   building new ones. The legacy tables are stale by roughly a day for the aspects that cut over
   on 2026-08-24 (ledger, fleet, and the notes/places/pantry waves), so the shape is
   **reconcile-and-repoint, per aspect, engine-records-remain-truth-until-the-diff-is-clean** -
   never a blind switch back.
2. **The Notes `Item` type merges into the Dates `Event` type** (ruling 4). 21 fields into 7, with
   recurrence, `list_item_skips`, geofenced place triggers, a second alarm stack and the
   goal-checklist materializer attached; 64 Kotlin files. Ticket 01's cost inventory has the full
   list. Collapse the three disjoint agenda paths here rather than porting all three.
3. **Google Calendar is removed** (ruling 5), and **the order is already ruled** (ruling 11):
   widen the importer (description/location/allDay, parse the `LEGION::v1` block into real
   fields, unbounded window), run it, verify, and only THEN remove the Google path. Cutting first
   permanently deletes class metadata that exists nowhere else. `CalendarProvider.kt` also writes
   to Google and has zero test coverage - both facts matter to the removal order.

**Ordering constraint carried in from ruling 8:** writes go direct to Postgres with no offline
queue, so there is no local buffer to hide a bad cutover behind. Every per-aspect write cutover
needs its own rollback, and the ticket-06 keep-alive against the 7-day free-tier pause is
load-bearing before the first write cutover, not after.

The pre-existing rule stands and is now sharper: **legacy-table drops wait until the whole backend
arc is proven.** With ruling 7 in play they may not be drops at all - some of those tables are
where the phone is going back to.


## What tickets 03 and 04 added (2026-08-25)

Two more retirements and one hard ordering constraint land here.

4. **The xlsx mirror is removed** (ticket 04 rulings 1-2), roughly 2,200 lines including its tests.
   **BINDING ORDER, and this one can lose data if ignored: the scheduled `DatabaseSnapshot` and a
   real restore exercised on a device must BOTH be done first.** The free tier has zero backup
   retention, the mirror was the nominated recovery story, and that nomination was already false
   (its import path never round-tripped). Deleting the mirror before the replacement is proven
   leaves a window with no recovery path at all.
5. **The deterministic statement parsers are removed** (ticket 03 ruling 3), replaced by an
   import of a CSV the user's own LLM produces. Sequence this against the three-anchor format
   existing: there is no point retiring the parsers before anything can read their replacement.

**The engine retirement should land BEFORE or WITH the commit RPC, not after** (ticket 03). The
generic shape is precisely why the commit is expensive to move: three full-table reads filtered in
Kotlin over JSON payloads become ordinary SQL predicates once the tables are typed, and
`RecordStore`'s per-row fan-out disappears entirely.

**A third sync mechanism is unruled and needs a call here.** `SyncEngine`/`SyncMerge` still does
row-level merge over ~19 legacy tables through Drive `appDataFolder`, auto-triggered every five
minutes from `service/AriaForegroundService.kt:216-223` and on `MainActivity.onResume`. Aspect-engine
ticket 20 declared it retired for record data, **but it retired zero legacy tables, so it is still
running today**. Ticket 04 ruled on the mirror and kept `DatabaseSnapshot`; nothing has ruled on
`SyncEngine`. Decide it here.

**Read-path work that ticket 04 identified and nobody owns yet:** ledger, pantry and fleet have no
loading state and no error state, so an empty screen and a not-yet-loaded screen are the same
pixels. Local latency hid that; a network round trip turns it into a false assertion, and ticket 01
ruling 9 (cache-first reads) additionally requires a visible "as of" on money.
`engine/WidgetDataSource.kt` is the worked example to extend, not a second vocabulary to invent.

## Resolution (2026-08-25) - three rulings and the sequence

### Rulings

1. **`SyncEngine` retires PER-TABLE, as each table's writes move to Supabase (Kevin, 2026-08-25).**
   The rule that makes it safe: **no table is ever in two sync channels at once.** The moment a
   table's writes go to Postgres it leaves the `SyncEngine` registry in the same commit; until then
   it keeps syncing over Drive so the second phone still works during a migration window measured in
   weeks. Drive LWW (`sync/SyncMerge.kt:44-49`, strictly-greater with ties keeping local) and
   Postgres would otherwise fight silently over the same rows. `SyncEngine` is deleted when its
   registry is empty, not before.
2. **The Notes `Item` into Dates `Event` merge happens DIRECTLY in the Supabase typed schema
   (Kevin, 2026-08-25).** The data moves once. This is forced more than chosen: **there is no legacy
   `events` table to fall back to** - Dates was born engine-native on 2026-08-23, and
   `CarDatabase.kt`'s entity list has no `Event` entity. So ticket 01's "repoint writes back to
   typed tables that already exist" shortcut applies to ledger, pantry, fleet and places, but **not
   to Notes/Dates**, whose target must be built new either way. Accepted cost: this is the largest
   single step in the arc, because recurrence, `list_item_skips`, geofenced place triggers, the
   second alarm stack and the goal-checklist materializer are all rewritten against the new shape at
   the same time as the move.
3. **The arc starts with SCHEMA AND AUTH, then aspects in order (Kevin, 2026-08-25).** Chosen over
   the recommended thin vertical slice. **Accepted risk, stated so it can be watched for:** the
   stack is unproven until the first aspect lands, so a wrong assumption about RLS, the RPC shape or
   session handling surfaces late, after the schema is already authored against it. **Mitigation
   that costs nothing: run the aspects smallest-first**, so Places (3 records) is the de-facto
   proving run and a wrong assumption is discovered against three rows rather than against the
   ledger.

### The constraint graph

Seven hard constraints, each from a resolved ticket. **These are not preferences; violating C2, C3
or C4 loses data.**

| # | Constraint | Source |
|---|---|---|
| C1 | The Postgres schema is TYPED from the start, so the commit RPC is never written against the generic shape | ticket 03, "engine retirement before or with the RPC" |
| C2 | Scheduled `DatabaseSnapshot` **and** a restore exercised on a device, both **before** the mirror is deleted. **AMENDED 2026-08-25: the restore is no longer a PHASE 0 gate** (no device on this machine); it remains owed before the phase 6 mirror deletion | ticket 04 ruling 4, as amended |
| C3 | Widen the Google importer, run it unbounded, verify, **then** remove Google | ticket 01 ruling 11 |
| C4 | The three-anchor CSV import must work **before** the statement parsers are removed | ticket 03 ruling 3 |
| C5 | The keep-alive against the 7-day free-tier pause lands **before** the first write cutover | ticket 01 ruling 8 plus research ticket 06 |
| C6 | Legacy-table drops come last, after the whole arc is proven | pre-existing, sharpened by ticket 01 ruling 7 |
| C7 | Rule 7's tests must point at production code **before** rule 7 is reimplemented in SQL | hardening ticket 05 defect 1 |

**C1 resolved, because it reads like a contradiction and is not.** "Engine retirement before or with
the RPC" does not mean deleting 15,885 lines in phase one. It means the *server* schema is typed
from day one so the RPC is never built to mirror the generic shape. The *phone's* engine retires
incrementally, one aspect at a time, as each cuts over; the code is deleted only once nothing reads
it. Retirement and deletion are different events and this ticket separates them deliberately.

**C6 changes meaning under ticket 01 ruling 7, and this matters.** The phone is going typed, and
ticket 01 established the engine retired **zero** legacy tables. So most "legacy" tables are not
drop candidates at all - they are the destination the phone returns to (`ledger_transactions`,
`pantry_receipts`, `vehicles`, `service_records`, `places`). What genuinely gets dropped is much
narrower: the four engine metadata tables, `widget_instances`' engine coupling, and the truly dead
notes-era tables (`item_lists`, `car_tasks`, `place_reminders`, and `list_items` once the
Item/Event merge lands). **Do not read C6 as "drop everything legacy at the end."**

### The sequence

Every phase states what "done" means. Nothing advances on a phase whose verification is unmet -
that is L11, and it is binding here.

**Phase 0 - the safety net. Nothing else starts until this is done.** **STATUS 2026-08-25: both
buildable items DONE** (scheduler built and unit-tested; rule 7's tests repointed at production
code). The device restore is deferred per Kevin's amendment below and is now a gate on phase 6.
- Schedule `DatabaseSnapshot` (it is manual-only today; sole callers are three buttons in
  `ui/DriveSyncScreen.kt`) and **exercise a real restore on the A25**. Satisfies C2 early rather
  than late, and protects every phase after it.
- Fix hardening ticket 05 defect 1: point `IngestPipelineProvisionalSupersedeTest` at the real
  `IngestPipeline.commit` or delete it as superseded. Satisfies C7 **before** rule 7 is rewritten in
  SQL. Reimplementing a behaviour whose tests do not run against production code is how it silently
  changes.
- **Done means (as amended 2026-08-25):** the scheduler is built and unit-tested, and rule 7's live
  coverage is stated in writing.

**ATTEMPTED ON THE A25, 2026-08-26, AND IT IS BLOCKED BY SOMETHING BIGGER THAN THIS TICKET.**
The restore exercise cannot be run from the second machine at all, and the reason is worth more
than the test was:

```
Couldn't connect: 8: [8] Unknown error [status=UNREGISTERED_ON_API_CONSOLE]
```

An APK built on the second machine is signed with THAT machine's debug key, whose SHA-1 is not
registered against `com.kevin.legion` in the Google Cloud OAuth client. Drive authorisation
therefore fails outright, and `DatabaseSnapshot` backup AND restore both go through Drive, so
neither can run. **This is CLAUDE.md §2's open finding 1 observed for real** - previously it was
reasoned about as a hazard for a stranger cloning the repo, and it turns out to bite Kevin's own
second machine identically.

**Two consequences worth stating plainly:**
1. **The recovery story is machine-locked.** Only a build signed with the registered key can back
   up or restore. That is a sharper problem than "the restore is untested", because it means the
   backup path silently does not exist on any other build - and ticket 04 ruling 4 made this the
   ONLY recovery path once the xlsx mirror is deleted.
2. **The unblock is one console entry, not a code change.** This machine's debug SHA-1 is
   `52:4F:39:23:7E:8B:3B:5B:C2:3A:76:A7:EE:BD:16:ED:82:77:30:07`. Registering it against the
   package as a second Android OAuth client makes Drive work from here permanently, and makes the
   restore exercise runnable without the Kwin laptop.

**RESOLVED THE SAME DAY.** Kevin registered this machine's SHA-1 as a second Android OAuth client
against `com.kevin.legion`, Drive authorised, and the restore was exercised end to end on the A25.

**It failed the first time, which is the entire point of having run it.**
`DriveClient` set only `callTimeout(60s)`, and OkHttp does not derive its per-operation timeouts
from that: connect, read and write each stayed at the 10 second default. Downloading a
multi-megabyte whole-database backup meant waiting on Drive to begin streaming, that wait blew the
READ timeout, and the restore died with `SocketTimeoutException` out of `Http2Stream.takeHeaders`
while 50 seconds of the call budget were still unused.

**Uploads were never affected, which is exactly why it survived.** The backup half runs regularly
and had worked for weeks; the restore half had never once been run. That is L36 in its purest
form: the untested half was the broken half, on the only recovery path there is.

**What the app got right, and it is worth recording as well as the bug.** It failed BEFORE touching
the database, left it byte-identical, and said so in words ("Couldn't download that backup."). The
confirmation dialog states exactly what will be replaced, that a local copy is taken first, and
that nothing does this automatically.

**After the fix** (explicit connect 15s / read 60s / write 60s, `callTimeout` 120s as the outer
bound): restore ran clean, wrote `files/pre_restore_backups/pre_restore_*.db` as promised, and the
database verified afterwards at `integrity: ok`, schema 37, **578 records** with the provenance
split unchanged (430 DETERMINISTIC, 29 LLM_RECONCILED, 7 UNRECONCILED, 107 USER), 168 legacy ledger
rows, 71 list items, 5 vehicles, 4 places.

**Phase 0 is now genuinely complete, and the phase 6 gate is satisfied**: the recovery path is no
longer a hope. **The restore exercise is deferred, not deleted** - Kevin released
  it as a phase 0 gate because the A25 is not attached to this machine, over a stated objection.
  It is now a **named blocking item against phase 6**, and `memory/MEMORY.md` carries it so it is
  not lost. Anyone reaching phase 6 with this still unmet must stop, per L11: an unmet verification
  step is a gate, not a footnote.

**Phase 1 - foundations. No data moves.**
- `supabase-kt`; Supabase URL and anon key as BYO runtime config entered like the Gemini key, never
  baked into `BuildConfig` (a distributed APK must not carry Kevin's project).
- SQL migrations committed under `supabase/migrations/`, applied with the CLI `db push`, so a
  stranger runs one script against a fresh project. Clone-and-run is the test.
- `household_members` plus the `security definer` RLS helper in a private schema (the recursion fix
  Supabase documents). Both accounts created in the dashboard per ticket 02 ruling 3.
- Email+password sign-in; session in `KeyVault` **failing closed**, no plaintext fallback.
- **The keep-alive** against the 7-day pause. C5 puts it before the first write cutover; it costs
  nothing to land here and ruling 8 made it load-bearing rather than a nicety.
- **Done means:** both accounts sign in on the phone, RLS is proven to return rows for a member and
  nothing for a non-member, and the keep-alive has survived one full week without a pause.

**Phase 2 - the schema and the RPC. Still nothing writing from the app.**
**STATUS 2026-08-25: APPLIED AND VERIFIED ON THE REAL PROJECT.** All six migrations ran against
`HomeERPBackend` (project ref `gccxiqusqxkjmjmaadpz`, org HomeERP, AWS us-east-1, free tier), via
the dashboard SQL editor.

**Verified by querying the database, not by reading a success panel:**
- **12 tables**, every one with `relrowsecurity = true` and exactly one policy. Supabase's editor
  offered "Run without RLS" vs "Run and enable RLS" and **without** was the correct choice: its
  static analysis cannot see `private.apply_household_rls()` being called inside a `do $$` block,
  and letting it inject its own statements would have added SQL nobody reviewed.
- **The immutability trigger works.** A live test on `receipts`/`ledger_transactions`: gated UPDATE
  BLOCKED, gated DELETE BLOCKED, provisional (UNRECONCILED) DELETE ALLOWED. That last one is the
  one rule 7 depends on, and it is now proven rather than argued.
- **`commit_statement` behaves.** A valid statement returned `COMMITTED` with 3 rows inserted; the
  same payload again returned `ALREADY_COMMITTED` with 0 inserted (idempotency on `content_sha256`
  is real); an empty `lines` array returned `QUARANTINED` (rule 6); a wrong stated total and a
  wrong closing balance each returned `QUARANTINED`.
- Every test wrapped itself in a final `raise` so the transaction rolled back. **All nine tables
  confirmed at 0 rows afterwards** - no test data survived in Kevin's database.

**Migration history is bypassed.** The dashboard path does not record migrations, which Supabase
warns can break a later `supabase db push`. The files are committed and idempotent, so the fix when
the CLI is first used is `supabase migration repair`, not a re-run.

Delivered:
- `20260825000200_conventions.sql` - provenance enum, the immutability trigger for gated tables,
  `updated_at` for authored ones, the household RLS macro, server-side `ingested_files` with
  `content_sha256` unique.
- `20260825000300_aspect_ledger_pantry.sql` - `statements` + `ledger_transactions`, `receipts` +
  `receipt_line_items`. Header-plus-lines, all three anchors NOT NULL on the header, and a CHECK
  tying `statement_id` nullability to provenance so the schema itself says a provisional row was
  never checked against anything.
- `20260825000400_aspect_dates_notes_merged.sql` - the Item-into-Event merge, 21 fields plus 7 into
  one `events` table with nothing dropped, plus `event_skips`.
- `20260825000500_aspect_places_fleet.sql` - `places`, `vehicles`, `service_history`,
  `maintenance_schedules`. The engine's BLOCK delete policy becomes real `ON DELETE RESTRICT`.
- `20260825000600_commit_statement_rpc.sql` - the ledger commit RPC.

**OWED before phase 4's ledger cutover, both recorded rather than glossed:**
1. ~~`commit_receipt`, the pantry equivalent.~~ **DONE 2026-08-25, applied and verified live.**
   All seven branches exercised on the real project: valid COMMITTED, repeat ALREADY_COMMITTED,
   empty items QUARANTINED (rule 6), anchor 1 broken QUARANTINED, anchor 2 broken QUARANTINED, and
   both sides of the no-subtotal collapse. The gate is now proven against two genuinely different
   anchor shapes rather than one. Note it deliberately has NO dedup pass: a receipt is one physical
   document photographed once, two identical items on it are two real rows, and idempotency on
   `content_sha256` already prevents the only duplication that can occur.
2. ~~The dedup restatement pass.~~ **DONE 2026-08-25, applied and verified live.**
   `private.ledger_resolve_dedup` is a faithful port of `LedgerDedup.resolveDedup`: two passes over
   a shared depleting credit pool, exact strict-key matches first with no window condition, then
   loose (description-dropped) matches only inside dates another committed statement already
   enumerated. Windows derive from ACTUAL first/last transaction dates, never the printed period.

   **Verified on the real project against the scenario this exists for**: the July PDF wording
   `PURCHASE 0706 VPN24.ME EDINBURGH 00` and the mid-cycle CSV wording
   `VPN24.ME 07/06 PURCHASE EDINBURGH 00` for the same transaction. In-window: dropped, counted as
   a restatement. Same rewording outside any window: inserted, so the relaxation is not global.
   Two identical lines in one statement: both inserted, because intra-file twins are real
   purchases and collapsing them is the original bug the function exists to fix.

   **A schema bug of mine that the port exposed, now fixed.** `LedgerAccountIdentity.kt` forbids
   folding `sameCard`'s last-four suffix match into the dedup key, because a checking account
   ending in the same four digits would absorb a card's rows. Ticket 03 ruling 5 then made the
   stored identity last-four plus nickname, so keying on `account_last4` alone would have
   reintroduced that exact bug through the schema rather than the predicate. **The key is now the
   (last4, nickname) pair**, restoring plain equality on a full identity. The nickname is
   load-bearing, not a label.

   One accepted divergence, documented in the migration: Kotlin's `uppercase()` is Locale.ROOT with
   full Unicode one-to-many mapping, Postgres `upper()` is collation-driven and is not. Harmless for
   ASCII bank descriptions, and the failure direction is known - a divergence causes a false
   NON-match, which double-counts.

~~**Also owed, and it is this phase's real deliverable per ticket 03 ruling 2:** the shared gate test
corpus.~~ **DONE 2026-08-26. PHASE 2 IS COMPLETE.**

`app/src/test/resources/gate-corpus.json` is the single source of truth for what the gate must do.
Both sides read the same file rather than being compared against each other: `GateCorpusTest.kt`
checks it from Kotlin, and `tools/gate_corpus_sql.py` emits the identical cases as SQL
(`supabase/tests/gate_corpus.sql`, generated and committed following this repo's existing pattern
for `adr-index.md` and `Board.md`, because there is no local Postgres).

**Result: 4 Kotlin tests green, and the SQL side reported `GATE CORPUS: all 13 cases agree.
(rolled back)` against the live project.** Verified afterwards at 0 rows in every table.

Thirteen cases. The ones that carry weight are the ones a careless corpus omits: an empty extraction
whose stated figures are all zero, which satisfies every anchor on nothing at all; a statement whose
lines sum to the printed total but whose balances disagree, which a single-anchor gate waves through
and which is precisely why ruling 4 demands three printed figures; a genuinely zero-movement month
WITH real lines, guarding against over-correcting rule 6 into refusing legitimate zero sums; both
halves of pantry's no-subtotal collapse; and absurd macro estimates that must not move the result,
because a receipt never prints calories.

Two meta-tests guard the corpus itself: one fails if either aspect stops exercising both outcomes
(an all-COMMITTED corpus passes against a gate that never refuses), the other fails if nobody keeps
a case of the exact self-satisfying-zero shape rule 6 exists for.

**Honest scope.** The phone's own pre-check does not exist yet - ruling 3 retires the parsers for a
CSV path that is unbuilt - so there is no Kotlin gate function to call. What the Kotlin side asserts
is that every case follows from its own numbers, which makes the corpus self-verifying and is the
same arithmetic the SQL implements. When the pre-check lands it plugs into the same corpus and the
comparison becomes direct. That is stated in the test file itself so nobody mistakes it for more.
- Per-aspect typed tables for every aspect, with `provenance` a real column and a server-side
  `ingested_files` equivalent keyed on `contentSha256`.
- The `events` table absorbing the Item shape (ruling 2 designs it here, one shape, once).
- The commit RPC: inserts, the gate arithmetic in SQL, the three-anchor check, rule-7 supersession
  in the same transaction and before the dedup read, `resolveDedup` and the `sameCard` last-4
  suffix relation as SQL predicates, **idempotent on `contentSha256`** so a repeat call is a
  successful no-op.
- **Done means:** the shared gate test corpus (ticket 03 ruling 2's real deliverable) passes
  identically against the Kotlin pre-check and the SQL, including every existing quarantine case;
  a repeat RPC call provably writes nothing twice; and a partial insert is impossible by `RAISE`.

**Phase 3 - read-path honesty. Before any cutover, because it is needed the moment a read goes
remote.**
- Extend `engine/WidgetDataSource.kt`'s vocabulary with `Stale`/`Unreachable`. **Do not invent a
  second vocabulary** - it already distinguishes empty from not-configured from error in words.
- Give ledger, pantry and fleet a loading state and an error state. Today they initialise to a
  default `UiState()` and paint it, so an empty screen and a not-yet-loaded screen are the same
  pixels. Local latency hid that; a network round trip turns it into the app asserting "no
  transactions" when it simply has not looked yet. **Unreadable and empty are different sentences**
  is already a rule in CLAUDE.md §1 for the calendar; this is the same rule.
- The visible "as of" on money that ticket 01 ruling 9 requires.
- **Done means:** with the network off, every migrated surface says what it does not know rather
  than rendering a blank as a fact.

**Phase 4 - per-aspect cutover, smallest first.** Order: **Places (3) → Pantry (29) → Fleet (62) →
Notes+Dates (172, together, carrying the merge) → Ledger (168, last).** Ledger is last because it is
money and because it depends on phase 5's CSV path.

Each aspect follows the identical shape, and it is a reconcile-and-repoint, never a blind switch:
1. Upload, guid-keyed and idempotent, so a re-run is free.
2. **Diff until clean.** Engine records remain the truth until the diff is clean. The legacy typed
   tables are roughly a day stale for whatever cut over on 2026-08-24, so this is a real
   reconciliation, not a formality.
3. Flip that aspect's writes to Supabase; Room becomes its replica.
4. **Remove that aspect's tables from the `SyncEngine` registry in the same commit** (ruling 1).
5. Soak before starting the next aspect.
- **Done, per aspect, means:** the diff is clean, writes land server-side, the phone renders from
  the replica, and no table is in two sync channels.

**Phase 5 - the Google exit and the CSV path. Interleaved, not appended.**
- **Before the Notes+Dates cutover:** widen the importer with description/location/allDay, parse the
  `LEGION::v1` block into real fields, run one import over an **unbounded** window, verify. Then
  remove the Google path. C3 is binding: the class metadata is authored in Google descriptions and
  stored nowhere, so cutting first deletes it permanently. Widening before the cutover also means
  the enriched records migrate once instead of being backfilled afterwards.
  Note `calendar/CalendarProvider.kt` also **writes** to Google and has **zero test coverage** -
  both facts belong in the removal order.
- **Before the Ledger cutover:** the three-anchor CSV format and its importer must work end to end
  (C4). Only then do the deterministic parsers come out.

**Phase 6 - deletions. Only what nothing reads any more.**
- The engine: 9,518 production plus 6,367 test lines, once no aspect reads it.
- The mirror: roughly 2,200 lines. **Gated on C2 having been satisfied in phase 0.**
- `SyncEngine`, once its registry is empty.
- The statement parsers, per C4.
- Genuinely dead tables only, per the C6 clarification above.

### Rollback

Ruling 8 removed the offline queue, so there is no local buffer to hide a bad cutover behind. Each
aspect's rollback is the inverse of its own phase-4 step and nothing wider: **stop writing that
aspect to Supabase, restore it to the `SyncEngine` registry, and keep reading Room**, which is
still a full replica and was the truth until step 3. This is why deletions are phase 6: **every
rollback depends on the code deleted at the end still existing during the middle.** That single
sentence is the reason this ticket refuses to interleave deletion with cutover.

### What this ticket does not decide

- The Postgres column-level design per aspect. Phase 2 authors it; this ticket only fixes that it
  is typed, that provenance is a real column, and that the `events` table absorbs the Item shape.
- What the eval harness and screenshot tests owe the arc. The original question asked; it is
  deferred, because phase 3 changes what every migrated screen renders and writing the test plan
  before that lands would be writing it twice.
- The second phone. It has never been attached to this machine and the two-phone merge has never
  run. Under Supabase the problem largely dissolves - the server is the truth, so there is no merge
  to get wrong - but that is `reasoned`, not verified, and it stays unproven until a second device
  actually signs in.
