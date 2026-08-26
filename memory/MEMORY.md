# MEMORY.md

Dashboard for LEGION. **MEMORY.md wins for state, CLAUDE.md wins for rules.** Depth lives in the
library. Under 80 lines. MIDNIGHT_AI: see CLAUDE.md §1.

## START HERE - 2026-08-25 (late) - BACKEND-ERP IS FULLY DECIDED AND SEQUENCED. BUILDING IS NEXT.

**Kevin pivoted: the backend IS the ERP.** Supabase (BYO project per household, never
Kevin-hosted) becomes the system of record; the Android app becomes ONE consumer; a Windows/laptop
surface is coming; auth for two users, extensible. This reopens local-Room-primary at Kevin's own
initiative. Map: `.scratch/backend-erp/` - six tickets, feasibility research RESOLVED (free tier
fits; one hazard: the 7-day inactivity pause, manual resume; free tier has no backups so the xlsx
mirror carries recovery).

**Ticket 01 is RESOLVED - eleven rulings, all five of its questions answered.** Read
`.scratch/backend-erp/issues/01-what-the-backend-owns.md` before touching anything here. The four
that reshape the app, all Kevin's, three of them against the recommendation:

- **Postgres gets PER-ASPECT REAL TABLES**, not the generic shape translated. Enforcement moves
  server-side into real FKs/CHECKs/RLS.
- **The phone goes typed with it and the GENERIC ENGINE RETIRES.** Biggest cost in the map: it
  undoes the 2026-08-24 cutover of one day earlier. **Nothing is deleted until ticket 05
  sequences it.**
- **Todos become Dates events.** Note this is a TYPE MERGE, not a re-parenting: todos are the
  Notes aspect's `Item` type (21 fields, 64 Kotlin files) with recurrence, `list_item_skips`,
  geofenced place triggers and a second alarm stack attached.
- **Google Calendar is dropped entirely** - but the ORDER IS BINDING: widen the importer first
  (description/location/allDay + parse the LEGION::v1 block into real fields, unbounded window),
  verify, THEN cut. The class metadata lives only in Google descriptions and is never stored;
  cutting first deletes it permanently.

Also: writes go direct to Postgres with no offline queue (so the ticket-06 keep-alive is now
load-bearing, not a nicety); reads are cache-first with a visible "as of" required on money;
OBD live state, wake word/audio, photo files and widget layouts stay phone-only.

**Measured, not guessed** (two scouts, static counts, nothing compiled): the engine is 9,518
production + 6,367 test lines. **But it retired ZERO legacy tables** - `CarDatabase.kt:265-296`
still lists all 56 entities, only 6 of them engine, and the waves are additive-only. So ruling 7 is
largely REPOINTING WRITES BACK to typed tables that still exist, not building a typed layer from
nothing. Ticket 05 must reconcile-and-repoint per aspect (legacy tables are ~1 day stale for
whatever cut over on 08-24), never blind-switch.

**Ticket 02 (auth) is RESOLVED too - five rulings.** Sign-in is **email + password**: Google OAuth
needs two client IDs with one keyed to the SHA-1 cert (the trap already open against Drive), and
magic link rides Supabase's built-in email at **2 msg/hour, "not meant for production", no delivery
SLA** (now recorded as §7b of the feasibility research). Household RLS, all users see all rows, no
roles ever. **Both accounts are made in the dashboard** - no signup screen, no invite flow, zero app
code. **Personas and memories bind to the USER**, not the device, and memories gain a user tag for
attribution (CLAUDE.md §7: recalling her statement as his is the unfalsifiable-memory failure).
The Supabase session goes in KeyVault but **fails closed** - no plaintext fallback, unlike every
other secret slot, because phone-only killed the head-unit reason for it.

**Premise correction worth carrying:** the app has **no Google sign-in**. `sync/DriveAuth.kt` is the
Authorization API only - one `drive.appdata` scope, no token, no account, no email stored; sign-out
is a boolean. There is no human-user entity anywhere in the app, and no table has a human owner
column. Ticket 02's own question text claimed otherwise and was wrong.

**Ticket 03 (the gate) is RESOLVED - eight rulings, and it changed a CLAUDE.md rule.** The file
commit becomes ONE atomic Supabase RPC, **idempotent on `contentSha256`** - that is what closes the
gap ruling 8 opened, because `CANNOT_CLAUSE` is binary and has no word for "I don't know if it
landed"; with idempotency the phone retries instead of narrating. Gate arithmetic runs server-side,
phone pre-checks (two implementations, so they need a shared test corpus).

**The big one: the deterministic statement parsers RETIRE (Kevin's own proposal).** A statement goes
through the user's OWN LLM, which masks sensitive data and emits a CSV in a format LEGION defines.
Kills PdfBox, kills the Deno problem, and covers every bank instead of just DBS and BofA. **This
amends CLAUDE.md §4 rule 1** (amendment written in, marked decided-but-not-built). The gate gets
STRONGER to compensate: **three anchors** required (printed total, opening, closing), because an
LLM-produced CSV has lines and total from one process and a single anchor could be satisfied by a
self-consistent hallucination. Fewer than three anchors means rule-7 provisional. Rows tag
`LLM_RECONCILED`. Account identity is **last-4 plus a nickname** (last-4 collisions are real;
the nickname is what disambiguates).

**Ticket 01 ruling 10 was AMENDED the same day: photo files MAY leave the device**, to a private
bucket in the household's own Supabase project, because Edge-Function receipt vision cannot work
otherwise. OBD live state, wake word/audio and widget layouts stay phone-only.

**Three pre-existing defects found while grounding ticket 03, all filed there, none introduced:**
(1) `IngestPipelineProvisionalSupersedeTest`'s four cases have been testing DEAD CODE since cutover
3 - rule 7's live engine-path coverage is a single test; (2) a schema mismatch throws
`NoSuchElementException` past the narrow `catch` in `IngestPipeline`, so the file is never written
back to NEW; (3) `BofaStatementParser` has no non-empty guard and could vacuously pass a
zero-movement statement.

**Ticket 04 (mirror/cache) is RESOLVED - four rulings, and the grounding justified the boldest one.**
The **xlsx mirror is retired entirely** (~2,200 lines). Two facts made that cheap: **the import half
never round-tripped on a device** (export to Drive WAS verified on the A25 2026-08-23; a hand edit
landing in the app never was), and **`MirrorSyncActivity` has no in-app navigation** - debug-exported
only, unreachable in a release build. Hand-edit reimport dies with it, closing a real hole: a
blank-guid row minted a record with provenance `USER`, and a foreign guid was created as-is, so a
spreadsheet could mint records past the gate. Local cache is a **full replica** (569 records, roughly
285 KB estimated, three orders of magnitude under the free tier's ceiling).

**Recovery: `sync/DatabaseSnapshot.kt`, SCHEDULED, with a restore actually exercised.** The research
had nominated the mirror as the recovery story because the free tier has zero backups - **that was
already false**, resting on the import path that never ran. DatabaseSnapshot is built and reviewed
(whole-DB gzip to Drive appDataFolder, 3 generations, prunes only after upload confirms) but is
**manual-only** and **its restore has never been exercised on a device**. **BINDING ORDER: do not
delete the mirror until the scheduler and a real restore are both done**, or there is a window with
no recovery at all.

**Ticket 05 is RESOLVED and the map is CLOSED - all six tickets decided.** Three final rulings:
**`SyncEngine` retires PER-TABLE** (each table leaves the Drive registry in the same commit its
writes move to Postgres, so no table is ever in two sync channels fighting each other); the
**Item-into-Event merge lands directly in the Supabase schema** (forced: there is **no legacy
`events` table** to fall back to, Dates was born engine-native, so ticket 01's repoint-to-existing
shortcut covers ledger/pantry/fleet/places but NOT Notes/Dates); and Kevin chose **schema-and-auth
first** over a thin vertical slice, with the accepted risk that the stack is unproven until the
first aspect lands.

**THE SEQUENCE - seven phases. Do not reorder; C2, C3 and C4 lose data if violated.**
- **Phase 0, safety net, nothing else starts first:** schedule `DatabaseSnapshot` and **exercise a
  real restore on the A25**; fix hardening ticket 05 defect 1 so rule 7's tests point at production
  code BEFORE rule 7 is rewritten in SQL.
- **Phase 1, foundations:** supabase-kt, committed SQL migrations, `household_members` + RLS helper,
  email+password with the session failing closed, and the 7-day-pause keep-alive.
- **Phase 2, schema + the commit RPC:** typed tables, the `events` table absorbing the Item shape,
  gate arithmetic in SQL, three anchors, rule-7 supersession in-transaction, idempotent on
  `contentSha256`. The shared gate test corpus is the real deliverable.
- **Phase 3, read-path honesty, BEFORE any cutover:** loading/error/stale states and the "as of" on
  money. Extend `WidgetDataSource`'s vocabulary; do not invent a second one.
- **Phase 4, per-aspect cutover, smallest first:** Places 3 -> Pantry 29 -> Fleet 62 -> Notes+Dates
  172 (carrying the merge) -> Ledger 168 last. Upload, diff until clean, flip writes, drop that
  table from the `SyncEngine` registry in the same commit, soak.
- **Phase 5, interleaved:** widen the Google importer and run it unbounded BEFORE the Notes+Dates
  cutover; the three-anchor CSV path works BEFORE the parsers come out.
- **Phase 6, deletions only, last:** engine, mirror (gated on phase 0), `SyncEngine` once its
  registry is empty, parsers, and only genuinely dead tables.

**Two clarifications that prevent real mistakes.** Ticket 03's "engine retirement before or with the
RPC" does NOT mean deleting 15,885 lines up front: the SERVER schema is typed from day one so the
RPC is never built against the generic shape, while the PHONE's engine retires per-aspect and the
code is deleted only in phase 6. And **"legacy-table drops" no longer means what it used to** -
ruling 7 sends the phone back to those tables, so `ledger_transactions`, `pantry_receipts`,
`vehicles`, `service_records` and `places` are the DESTINATION, not drop candidates. Only the engine
metadata tables and the dead notes-era tables actually get dropped.

**Why deletion is separated from retirement:** every rollback depends on the code deleted at the end
still existing during the middle. Ruling 8 removed the offline queue, so there is no buffer to hide
a bad cutover behind; per-aspect rollback is stop writing to Supabase, restore the table to the
`SyncEngine` registry, keep reading Room.

**OWED, DO NOT LOSE: exercise a real `DatabaseSnapshot` RESTORE on the A25.** Kevin released it as
a Phase 0 gate on 2026-08-25 (the A25 has never been attached to the second machine, `adb devices`
is empty), over a stated objection - an untested restore is a hope, which is L36's exact shape. It
is **still owed before the phase 6 mirror deletion**, because deleting the mirror with no proven
replacement leaves a window with no recovery path at all. **Must be done from the Kwin laptop**
(debug keystore lives there; never uninstall to fix a signature mismatch).

**PHASE 0 IS DONE except the device half (2026-08-25).** Both buildable items landed and are
verified green on the current tree (2,549 tests, 0 failures, counted from the JUnit XML, plus
`compileDebugAndroidTestKotlin`):
- **Rule 7's tests now run against production code.** The four supersede cases had not called
  `IngestPipeline.commit` since cutover 3; they exercised `deleteSupersededProvisional`, which has
  zero production callers. Ported to `src/test` (Robolectric) calling the real `commit` and
  asserting on engine records. Rule 7 went from **1 live test to 5**. The old file's reason for not
  calling `commit` (the `CarDatabase` singleton) was **obsolete** - `RoomTestReset` already solved
  it. Closes hardening ticket 05 defect 1, which blocked rewriting rule 7 in SQL at phase 2.
- **`sync/ScheduledBackup.kt` exists**: app-lifecycle daily check (no WorkManager), called from
  `MainActivity.onResume`, 24h floor, never passes `overrideGuard`, install-scoped prefs, sealed
  `Outcome` with every branch worded, six tests. `DriveSyncScreen` shows last success, the
  "only while the app is open" caveat on BOTH branches, and any failure reason.

**PHASE 1 IS BUILT, NOT PROVEN (2026-08-25).** All of it compiles, tests green (2,576, 0 failures),
and `assembleDebug` produces an APK. **Nothing has touched a real Supabase project yet.**

- `supabase/migrations/20260825000100_household_and_rls.sql` - household roster, `security definer`
  membership helper in a `private` schema (the recursion fix), RLS, and a `public.keepalive()` RPC.
  **The absence of insert/update/delete policies IS the enforcement** - membership can only be
  granted in the dashboard. No role column, deliberately.
- `.github/workflows/supabase-keepalive.yml` - daily cron ping. **Deliberately NOT on the phone:**
  the pause triggers on no DB activity, and a phone ping only fires when the app is open, which is
  when there is already activity. Needs secrets `SUPABASE_URL` and `SUPABASE_ANON_KEY`.
- `backend/` - `SupabaseConfig` (BYO URL + anon key, never `BuildConfig`, NOT in KeyVault since the
  anon key is not secret), `SupabaseSession` (**fails closed** - throws rather than the plaintext
  fallback every other secret slot uses), `SupabaseClientProvider`, `SupabaseAuth` (email+password,
  sealed results including signed-in-but-not-a-member). UI on `KeyScreen`.

**Two build compromises, filed as hardening ticket 06, both verified by real builds:** supabase-kt
is pinned to **3.6.0** because 3.7.0 needs Kotlin metadata 2.4.0 and this project is Kotlin 2.1.0;
and Compose is `resolutionStrategy.force`d to 1.7.0 after the new graph drifted it to 1.9.0 and
broke four screenshot tests. The 1.9.0 requester was never identified.

**OWED BY KEVIN, blocks everything else in Phase 1:**
1. Apply the migration (`supabase db push`, or paste into the dashboard SQL editor).
2. Add the two GitHub Actions secrets, then run the workflow manually to prove it.
3. Create both accounts in the dashboard and insert their `household_members` rows.
4. Sign in on the phone and confirm the membership check.

**PHASE 2 SCHEMA IS APPLIED AND VERIFIED ON THE REAL PROJECT (2026-08-25).** Six migrations ran against
`HomeERPBackend` (ref `gccxiqusqxkjmjmaadpz`, org HomeERP, us-east-1, free tier) through the
dashboard SQL editor. **12 tables, every one RLS-enabled with a policy**, confirmed by querying
`pg_class`/`pg_policy` rather than by trusting the editor's success panel.

**The two things a local parser could never check are now proven on real Postgres.** The
immutability trigger: gated UPDATE blocked, gated DELETE blocked, provisional DELETE allowed (rule
7 needs that last one). And `commit_statement`: valid payload COMMITTED with 3 rows, the same
payload again ALREADY_COMMITTED with 0 rows, empty lines QUARANTINED (rule 6), wrong total
QUARANTINED, wrong closing balance QUARANTINED. Every test ended in a deliberate `raise` so it
rolled back; all tables verified back at 0 rows.

**Migration history is bypassed** by the dashboard path. First CLI use needs
`supabase migration repair`, not a re-run - the files are idempotent.

Ten tables. Kevin's three rulings encoded: **immutable for gated aspects** (ledger, pantry) and
editable for authored ones; **header-plus-lines** so a line cannot exist without the anchor it was
checked against; **all six aspects** at once, including the Item-into-Event merge (21 fields plus 7
into one `events` table, nothing dropped, plus `event_skips`).

Two design points worth not re-deriving: the immutability trigger blocks every UPDATE and blocks
DELETE **except on UNRECONCILED**, because rule 7 defines provisional rows as never asserted as fact
and transient - freezing them would stop rule 7 running at all. And there is deliberately **no
`reversed_by` column**, since setting one would require updating an immutable row; a reversal
carries `reversal_of` and "is this reversed" is a lookup.

`commit_statement` is the gate in SQL: rule 6's non-empty check FIRST (a zero-line extraction sails
through every anchor when the statement's own figures are zero), then the three anchors, then rule 7
supersession BEFORE the dedup read, then the lines. **Idempotent on `content_sha256`.** It returns
QUARANTINED rather than raising on a gate failure, deliberately: a raise would roll back the
quarantine record too, and the prohibition is on partial DATA, never on recording a rejection.

**OWED, all three recorded on ticket 05 rather than glossed:** `commit_receipt` (pantry's RPC, so
the gate is proven against two anchor shapes not one); the dedup **restatement pass** using
`enumeratedWindows`, without which a restated period double-counts; and the **shared gate test
corpus**, which is ticket 03 ruling 2's real deliverable - two implementations of the same
arithmetic now exist and nothing proves they agree.

**PHASE 1 IS DONE except the phone.** Secrets set and the keep-alive workflow run manually and
concluded success (2026-08-26 00:48Z, `workflow_dispatch`), so the daily 07:00 UTC cron is live.

**ONE user for now (Kevin, 2026-08-25).** The household model is unchanged, it simply has one
member; adding the second is a dashboard job whenever he wants it, and the schema gives clients no
write path to `household_members` by construction.

**RLS is proven in BOTH directions despite there being one account**, by impersonating a JWT `sub`
rather than needing a second real user: member sees 1 row, **non-member (valid JWT, not on the
roster) sees 0**, and **anon is DENIED at the grant level** ("permission denied for table places")
before RLS is even consulted. That is two independent layers, each demonstrated: the
`revoke all from anon` in the RLS macro, and the policy itself. Test self-rolled-back.

**THE A25 IS NOW REACHABLE FROM THE SECOND MACHINE** over wireless adb, and a build from here is
installed and running on it (2026-08-26). Uninstall/reinstall was authorised; **all data was pulled,
verified and restored** - backup with checksums and a README at
`C:\Users\kevin\legion-device-backup\2026-08-26\`.

**BUT Drive does not work from this machine, and that is a bigger finding than the test it blocked.**
A build signed with this machine's debug key fails authorisation with
`8: [8] Unknown error [status=UNREGISTERED_ON_API_CONSOLE]`, because its SHA-1 is not registered
against `com.kevin.legion`. That is CLAUDE.md §2's open finding 1 observed for real rather than
reasoned about. **Consequence: the `DatabaseSnapshot` restore exercise cannot run from here**, since
backup and restore both go through Drive - so it stays owed, and stays a gate on the phase 6 mirror
deletion.

**One console entry unblocks it.** This machine's debug SHA-1 is
`52:4F:39:23:7E:8B:3B:5B:C2:3A:76:A7:EE:BD:16:ED:82:77:30:07`; registering it as a second Android
OAuth client for the package makes Drive work from here permanently.

**Also still owed:** the Gemini key and every other `KeyVault` secret must be re-entered by hand.
The ciphertext restored fine but the Keystore key that decrypts it died with the uninstall, and the
app correctly reports "Gemini key: Not set" rather than pretending.

**RESUME POINT: Phase 2's three remaining items** - `commit_receipt` (pantry's RPC, so the gate is
proven against two anchor shapes not one), the dedup restatement pass, and the shared gate test
corpus, which is now writable because the SQL actually runs. Nothing in this map has been built - it is six
resolved decision tickets and a sequence. Deferred deliberately: the eval/screenshot test plan
(phase 3 changes what every migrated screen renders, so writing it first writes it twice) and the
second phone (never attached to this machine; Supabase likely dissolves the merge problem, but that
is `reasoned`, not verified).

Also landed 2026-08-25:
- **Calendar description blocks merged** (LEGION::v1 sentinel: machine fields reach the model,
  prose does not) - from Kevin's cross-session patch, plus a JSONObject-wrapping fix.
- **Home flip reverted to classic** (section below); the revert build is pushed but **NOT yet
  installed - the A25 dropped off adb**. Install MUST happen from the Kwin laptop (debug keystore
  lives there; never uninstall to fix a signature mismatch).
- **Wiki-notes research resolved**: video I3bpdgFJCUY = Ben Holmes's llm-wiki pattern (agent does
  the gardening nightly; wiki compiled weekly). Mapping: a Note record type on the Notes aspect,
  enrichment as a SubAgent batch, provenance-separated. `research/wiki-notes-second-brain.md`.
  Kevin wants this as his notes system - do not lose it.
- **Cross-interface memory requirement**: companion_memories follows the user to Supabase so
  Alfred remembers across phone/Windows. On the backend-erp map.

**Standing queue behind the pivot**: widget tap-through (widgets still do not navigate), legacy
drops (WAIT until the backend arc proves out - dropping local history first would be the bad day),
ticket 23 (deferred fleet entities), semantic recall (.scratch/ai-craft/02).

---

## PREVIOUS - 2026-08-25 - THE HOME FLIP REVERTED, ASPECT-ADD FIXED

**Classic home is back.** Kevin field-tested the pager as HOME overnight and ruled "kill it, revert
everything to classic." `LegionRoute.TODAY` is `NavHost`'s `startDestination` and the HOME hard key
again, exactly the pre-cutover-5 shape. **The pager is NOT deleted** - `LegionRoute.DASHBOARD` stays
a real route, demoted to an opt-in surface: a single "DASHBOARD" button on `TodayScreen`. The
pager's own HOME-page "CLASSIC" button is removed (pointless once TODAY is home again).
**Also fixed: "adding an aspect doesn't work."** The pager's "+" page was exactly the stub ticket 18
called it - it wrote a bare `Aspect` row with no `RecordType`/`FieldDef`, so a created aspect could
never hold a record or a widget. Now a real create flow (aspect name, starter record type, 1-2
starter fields) committed through `EngineToolbox.manualCreateDraft` + `commitCreateAspect` - the
SAME write path `create_aspect`'s voice confirm handshake uses, one implementation not two.
Screenshot re-recorded (`pager-home-seeded-arrangement.png`, CLASSIC button gone from the header).
Full account: `docs/architecture/cutover5-2026-08-24.md`'s postscript. Owed on-device: the real
launcher tap lands on TODAY; the fixed "+" page produces a genuinely usable aspect on the phone.

---

## PREVIOUS - 2026-08-24 (night) - THE CUTOVER ARC IS COMPLETE

**The engine IS the app, and Kevin approved it on the phone ("i like it").** All five cutovers
merged and device-verified in one day: Notes+Places, Pantry (anchors now persisted), Ledger (gate
commits engine-side, atomic with rule-7 supersession), Fleet (anchors derived single-row,
ticket-29 drift dead by construction), and the home flip (DASHBOARD is MainActivity's start
destination; WidgetPagerActivity deleted; CLASSIC + per-aspect full-screen buttons keep every old
capability one tap away). 569 active engine records, zero duplicate guids, legacy tables frozen
writer-less but NOT dropped (soak first). Every cutover doc: docs/architecture/cutover{1..5}-2026-08-24.md.
**Known named gap: widgets do not navigate on tap** (generated screens have zero callers) - the
top follow-up. Then: legacy drops, ticket 23, the Supabase sync build (decided: BYO project,
sync+push, every push passes the compulsion test), semantic recall (.scratch/ai-craft/02).

---

## PREVIOUS - 2026-08-24 - THE ASPECT ENGINE SHIPPED, ALL DATA MIGRATED

**LEGION's spine is now the aspect engine.** Charted, decided, built, and verified in one
2026-08-23/24 run: `.scratch/aspect-engine/map.md` (21 of 23 tickets resolved; 17/18/19 `built`
owing small on-device verdicts; open: 22 cutover, 23 deferred fleet entities).

- **Room v37.** Engine tables (`aspects`/`record_types`/`field_defs`/`records` + guid identity),
  `engine/RecordStore.kt` is the ONLY writer of records. 13 field types, computed fields, the
  reconciliation gate rehomed as engine infrastructure.
- **ALL legacy aspect data copied onto the engine, additive-only, verified on the A25 by pulling
  the real DB**: Dates 160 (Google Calendar imports, one-way), Notes 12/12, Places 3/3, Pantry
  3+26 all `LLM_RECONCILED`, Ledger 161 `DETERMINISTIC` + 7 `UNRECONCILED` (1:1), Fleet 5 vehicles
  + 52 schedules + 1 OBSERVED + 4 ASSERTED service-history rows. **Old tables and screens still
  live; NOTHING reads the engine copies yet except the pager/mirror/meta-tools. Cutover is ticket
  22.** Carve docs: `docs/architecture/wave{1..4}-carve-2026-08-23.md` - read before touching.
- **The whole-app widget pager exists** (`ui/widgets/WidgetPagerActivity`, debug-exported only,
  seeded home, real data) but is NOT the app home yet. Grid: preset sizes, launcher semantics,
  seven on-device feel rounds with Kevin.
- **Voice: nine engine meta-tools + Flash clerk (silent under 4s, needs grounded date) + Pro
  schema generator with confirm handshake** live in `service/EngineToolbox.kt`. The 104-tool
  inventory: `docs/architecture/tool-inventory-2026-08-23.md` (33 die at cutover, 48 survive, 23
  need a call). Owed: one real voice round-trip.
- **Mirror/sync: Kevin ran the Drive probe - PASSED** (xlsx per aspect in his picked folder, rwt +
  hash verify, no quarantine). The xlsx files ARE the two-phone sync channel, row-merge by
  guid+updatedAt. Second phone never tested.
- **Owed on-device, small:** a Dates reminder actually firing (exact alarms armed); pager felt
  verdict in anger; voice round-trip writing a record.
- **New review muscle:** `/thermo-review` (Cursor's skill, adapted, MIT) + its detection lens
  wired into senior-dev and bug-hunter. Six Android skill packs vendored (adb/testing/gradle/
  debugging/security). Standing thermo candidate: `LiveToolbox.kt` (7,106 lines).
- **Process note that keeps proving itself:** every wave's reviewer found a real bug reading code
  (unwired migration, partial-copy flag, OR-across-rows fact drop); every UI bug needed the real
  phone. `BioDigestBuilderTest`'s Monday flake is dead (clock-injectable overload).

---

## START HERE - 2026-08-20 (late) - WAKE WORD LIVE, MEMORY DE-CARRED

**The wake word works and Kevin has used it.** "hey alfred" opens a turn, the assistant
acknowledges, "nevermind" closes it, and an ordinary "no" does not - all four verified by ear on the
A25. The greeting no longer assumes a drive and no longer mentions Chicago; Kevin: *"sounds good
now."*

**Read `.scratch/wake-word/map.md` first if you are picking up the wake word.** 5 of 12 tickets
resolved tonight.

**Four defects were found by RUNNING it, none by the compiler or the suite:**
1. `WakeWordPreferences` had **zero writers** - the engine was complete, wired, and unreachable.
2. The Vosk model had never been fetched onto this machine (gitignored). Debug APK 78MB -> 120MB.
3. The grammar was hardcoded to **"hey moose"** while the new Settings row said "hey alfred".
4. **The wake word went permanently deaf after the first conversation of every launch** - the
   greeting counts - because `ConversationState.isBusy` going false is not the same fact as "the
   microphone is free". Only visible because the silence detector had shipped an hour earlier.

**Still open and only Kevin can close them:**
- **Does it fire in the JEEP?** It did not, before tonight. The engine now opens
  `VOICE_COMMUNICATION` with hardware AEC/NS/AGC instead of Vosk's hardcoded `VOICE_RECOGNITION`.
  **Untested at road speed**, and the deaf-after-first-conversation bug may have been the real cause.
- **The battery number.** `.scratch/wake-word/issues/03-measure-the-battery-cost.md`. A contaminated
  19-minute window suggested ~0.7%/hour; treat that as a smell, not a measurement. Needs a clean
  overnight run: screen off, off charger, phone left alone.

**Memory: surveyed, then fixed rather than charted** (Kevin's call). Consolidation, reflection and
human-like forgetting were ALREADY running. The defect was that `companion_memories` is keyed by
`vehicleId` and recall read only the active car's slice, so **46 memories about Kevin were invisible
whenever the Jeep was active**. Fixed in a WHERE clause, no schema change.

**New: `memory_audit` (Room v27).** Records memory writes, deletes, recalls (query + every memory
handed to the model) and the lines the assistant SPEAKS. Pull it with
`adb exec-out run-as com.kevin.legion cat databases/legion_database` and read the table - that is
how the mileage bug got diagnosed.

**KNOWN GAP: the trail can miss a spoken line.** It depends on the API returning
`outputTranscription`, and a turn was observed speaking audio with none - it missed the very
greeting Kevin approved. Do not assume silence in the trail means silence from the assistant.

**Open and unexplained: `.scratch/hands-and-senses/issues/20-it-said-142k.md`.** The assistant said
the Jeep was at 142k when the record says 227,612. The data is correct everywhere, that figure is in
no table, the tool returns the right label, and it could not be reproduced. The audit trail now
exists precisely so the next occurrence can be read rather than guessed at.

## START HERE - 2026-08-20 - SPOTIFY VOICE, INSTALLED BUT NOT EXERCISED

**Ten of twelve tickets on `.scratch/spotify-voice/` are built, merged to `dev` and pushed.** Suite
**1747 green** (re-run 2026-08-20 on the Kwin laptop). The APK is now **installed on the A25 and
hash-verified** - it launches, `AriaService` starts, Room v26 opens with no migration error. That is
all the install proves: **not one Spotify path has been spoken to.** Everything below is
`built`+`tested`+`installed`, nothing is `on-device` in the sense of having been USED.

**Do this FIRST:**
1. **Re-approve Spotify in Setup.** `SCOPES` went 4 -> 13 (ticket 01), so the existing grant is stale
   BY DESIGN and `play_music` refuses until the browser hop is done. Do it at a desk. If it is
   discovered in the car, ticket 01 failed at its own purpose.
2. ~~**Install from the OTHER laptop.**~~ **DONE 2026-08-20, and the instruction was written in a
   frame that inverts when you move.** It said "this machine" / "the OTHER laptop" from the SECOND
   laptop, so read from anywhere else it points the wrong way, and it cost a failed attempt. Named
   absolutely: **the A25's install is signed by the FIRST (Kwin) laptop's
   `C:\Users\Kwin\.android\debug.keystore`**, SHA-256
   `4419FEDD4965BB5FD4250DD008266D978DF1CD61A02A1E4E88504B64D05F2FF3`. Verified by pulling the
   running `base.apk` off the phone and reading its signer with `apksigner verify --print-certs`,
   not by trusting a note. **Only the Kwin laptop can `adb install -r` onto the A25.** **NEVER
   uninstall to get around a signature mismatch** - `sync/` has still never executed, so the phone
   holds the only copy of 18,645 OBD samples, 148 ledger rows and the Keystore-sealed Gemini key.
   Check the signer; do not deduce it from whichever machine you happen to be sitting at.

**What landed:** the App Remote spine (it CREATES an active device rather than needing one, so
asking for music with Spotify closed should now work - **the headline claim, never run**), a
20-action `control_music` (queue, like, unlike, follow, shuffle, repeat, seek, restart, add to
playlist, more from this artist), playlists matched against his OWN library before the catalogue,
now-playing read from Spotify's own pushed state, `play_music` naming what it actually picked, and
`legion_history` rows that can finally be replayed.

**Left:** ticket 11 (the recommender - Kevin's own call: least important thing on the map) and
ticket 12 (ship pass, which is the on-device test list).

**Two numbers nobody has measured:** the playlist fuzzy-match threshold (0.6) and its cache TTL
(15 min). Both were tuned against unit tests, never against a spoken transcript.

**A research claim was DOWNGRADED:** the "Feb-2026 dev-mode cull" in
`.scratch/spotify-voice/research/01-api-capability-surface.md` could not be re-verified from primary
docs, and a confirmed-dead endpoint renders an identically normal reference page - so page
appearance proves nothing. `/artists/{id}/albums` may 403 on the real Client ID; ticket 13 degrades
to an honest spoken failure if it does.

## START HERE - 2026-08-19

**Second laptop is live** (`C:\Users\kevin\AndroidStudioProjects\legion`, Studio at the default
`C:\Program Files\Android\Android Studio`). Builds and the full suite run there; **the A25 has
never been plugged into it**, so nothing built there can be verified on-device from there.

**`open_navigation` shipped to `dev` (`b210ac3`, merged `6513ec3`)** - drive-test ticket 03. The
assistant could not open a map at all and said it had; now `location/NavigationController` fires
`google.navigation:`/`geo:` and its success is derived from whether `startActivity` ran.
**Every on-device box on that ticket is still unticked.**

**The suite is GREEN: 1641 tests, 0 failures.** `BioDigestBuilderTest` passes now - the
"known-failing, pre-existing" line below is STALE, do not repeat it.

## START HERE - 2026-08-18 night

**SWEPT 2026-08-19: all 39 open tickets across 9 maps were verified against the tree by five
readers. Exactly ONE was stale** (quant-viz 17, four of its five decisions built; narrowed to the
fifth). Everything else is genuinely open, waiting on Kevin, or waiting on a device, a car or a USB
cable. **The repo is NOT ahead of its docs this time** - the tickets' own notes were honest.

**Everything below is committed on `feat/mission-control` and installed on the A25, hash-verified.**
Nothing has been looked at on the phone. That is the whole outstanding risk.

**Look at these first when you pick it up:**
1. `.scratch/goal-keeping/map.md` - charted tonight, 8 tickets, blocking wired. Start at
   [What "on track" actually means](../../.scratch/goal-keeping/issues/01-what-on-track-means.md);
   the research ticket was fired at a subagent and its findings land in
   `.scratch/goal-keeping/research/08-computable-metrics.md`.
2. **The alarm pane's contrast, on the phone.** It is the first thing in the app to read
   `errorContainer`. That colour colliding with `surface` is what once drew every screen's body text
   in quarantine red, and only an APK install caught it.
3. ~~**`.scratch/proactive-mode/issues/09-fgs-start-delay.md`** - a 123s `startForeground` is a
   crash~~ **STALE. Downgraded by its own author in `65884a0` (2026-08-17) and still open as a
   MISREADING CANDIDATE**: a second `dumpsys` read showed `startForegroundDelayMs:554912` on a
   demonstrably healthy running service, so the field's meaning is unestablished. `startForegroundCompat()`
   is already first in `onCreate`. Unfixed AND unconfirmed as a bug - do not report it either way.

**Shipped tonight, all unverified on-device:** driver-editable playbooks + one priming resolver for
both answer paths; a memory screen (read + delete) and a playbook editor; temperature as a Setup
choice; the ALARM tier wired end to end; the proactive choke point plus a master switch that is
reachable by a human for the first time.

**Two live corrections to this repo's own docs.** CLAUDE.md sec 5 says Room v21 - a ticket
references v23/v24. Sec 10 says `ui/` is a clean slate - it has 23 screens and 13 subpackages.

**Audit, 2026-08-18:** 211 tickets swept. Still unbuilt: the deck control migration (7 files still
on raw Material), restricted-battery detection so reminders cannot silently die, three doc rules
decided and never written down (the CLAUDE.md sec 7 read-through guardrail is the important one),
and quant-viz's two vanished sparklines. 42 tickets unresolved across 10 maps; 34 are takeable.

**Known-failing test, pre-existing:** `BioDigestBuilderTest > bodyweight reports a weekly average`.
Fails at HEAD with the session's work stashed - verified, not assumed. Everything else is green
(1546 tests).

## Status as of 2026-08-15 (session 8)

- **THE PHONE CHANGED. It is a Samsung Galaxy A25 (`SM-A256U`), Android 16 / SDK 36.** Migrated
  2026-08-15; Kevin: "a25 is the real phone now". The **OPPO A17k (`CPH2471`) is RETIRED** - it still
  holds a full copy of the database as a fallback, so do not wipe it, but **never write to it.** Both
  phones were identical at the moment of migration and **`sync/` has still never executed**, so
  anything written to the A17k diverges silently and nothing reconciles it.
  - **Migration verified row-for-row**: 5 vehicles / 54 maintenance items / 2 service records /
    18,645 obd_samples / 148 ledger rows / 188 ingested_files, and **totals identical to the cent on
    both sides.** WAL was checkpointed into the main file before the copy and the target's stale
    `-wal`/`-shm` deleted, so no mismatched journal could replay.
  - **The Gemini key did NOT come across** - it is sealed by the A17k's hardware Keystore, which is
    device-bound by design. Drive authorisation and runtime permissions (mic, calendar) also need
    re-granting.
- **Two device facts that invalidate prior assumptions, both measured:**
  - **384 x 832 dp, not 360 x 806.** Every layout figure in `.scratch/mission-control/` was measured
    against the A17k - the 560dp content budget, the 328/159dp tiles, the 7-character hero. Not
    wrong, **unverified at this size.**
  - **Animation scales are 1.0, not 0.0.** The A17k froze every infinite animation, so the entire
    mission-control motion vocabulary was dormant. **That motion has never been observed by anyone,
    on any device, and it is now running.** Treat as untested, not as shipped-and-fine.
- **SIX domains: fleet, ledger, pantry, body, notes/lists/calendar, plus goals/advisors.** Tabs:
  Today, Money, Body, Fleet, Notes, Setup. **1485 unit tests, 2 FAILING** (2026-08-17) - both
  `BioDigestBuilderTest`, proven pre-existing by running that class alone at HEAD in a clean
  worktree. The old "1474 green" line was wrong. **The suite is NOT green. Do not claim it is.**
- **Room is v25.** v24->v25 indexed `obd_samples` on `(vehicleId, pid, timestamp)` - the table had
  **ZERO indexes at 18,694 rows**, so the FAULTS drilldown read **1.68M rows to draw one screen**
  and the old import hang was 11,511 full scans. **Verified on device: the plan is now
  `SEARCH ... USING INDEX`, was `SCAN` + temp sort.**
- Earlier: **v24.** v23->v24 closed the `categoryPending` default drift (annotation only, empty
  migration body, **verified opening on the A25**). v22->v23: `drives` (the drive-boundary object).
  v21->v22 landed 2026-08-16: `code_clear_events` (clear-DTC), additive, SQL
  verified byte-identical to the generated schema AND applied to a pulled copy of the real device
  DB (47->48 tables, zero DDL changes, zero row drift, integrity clean). **v20->v21 predates this
  session and is unaccounted for here** - read `app/schemas/` rather than trusting this line.
- Earlier: v19->v20 landed 2026-08-15 (fleet-maintenance): `intervalSource` + `deleted` on
  `maintenance_items`, `engine` on `vehicles`, and `cost` REAL -> `costCents` INTEGER on
  `service_records`. That last one is **non-additive**, the map's single stated exception to §5,
  permitted only because the column was **proven empty first** (0 of 2 rows). Proven against a COPY
  of Kevin's real data, then verified on-device.
- Branch **`feat/mission-control`** holds this session's work. `dev`/`main` far behind. CLAUDE.md §8:
  Claude never pushes `main`, never opens or merges that PR.

## Blocking

- ~~Notification-listener access missing~~ **GRANTED by Kevin 2026-08-16.** Media transport
  (pause/skip) should now work for everything, not just Spotify. **Untested since granting.**
- **Onboarding has no screen. Firebase not wired**, so a swallowed exception is invisible.
  **Crisis resource is US-only (988).**
- ~~Google console work still needing Kevin~~ **DONE** - tickets 09 and 11 are both resolved; the
  consent screen is in production and the Gmail scope is granted on-device
  (`library/decisions.md:2379-2405`). This line was stale for days. Drive OAuth CLEARED 2026-08-13.
- **NEW, found 2026-08-16 while verifying the Gmail tools: mail can still reach permanent memory.**
  The read-through rule is enforced on the episodic path (the whole turn is dropped) but **`remember`
  is not gated on it** - it writes `MemoryEntry` directly via `AriaBrain.kt:158-169`. Ticket
  `.scratch/google-account-integration/issues/21-remember-leak.md`.
- **Ticket 07 on `.scratch/android-auto/` needs Kevin**: settled decision 1 was taken on a premise
  since falsified, so "two surfaces, deliberately" has to be re-taken.

## Untested / unverified

- **NOTHING on the Android Auto surface has touched a head unit.** APK installed and hash-verified;
  never plugged in.
- **NO ALARM HAS EVER FIRED.** **`sync/` has never executed.** **OBD, wake word, proactives never
  run**; wake word CANNOT (`assets/vosk-model/` is a README only).
  - **CAUSE FOUND 2026-08-17 and fixed in `80a1758`:** nothing started `AriaForegroundService`
    except the Settings toggle - not app launch, not boot - so after any reboot or process death
    the assistant was dead while every surface read On. The 12h run proved it SURVIVES once
    started (same pid, same starttime, 6% battery over 7h53m, bucket still 10).
    **THE REBOOT WAS DONE 2026-08-17 AND THE FIX HELD** - the service came up from `BootReceiver`
    (`tempAllowListReason: BOOT_COMPLETED`, callingUid 1000, `startForegroundCount=1`,
    `isForeground=true`, nothing thrown), `on-device`. **Still unverified: whether the boot start
    omits the microphone FGS type** as the commit claims - LEGION was open on screen, so
    `types=0x91` may be `MainActivity.onResume`'s promotion. Needs a reboot nobody opens the app
    after. **NEW: that same record showed `startForegroundDelayMs:123489`** - 123s against a 10s
    platform window, a latent fatal `ForegroundServiceDidNotStartInTimeException`; ticket
    `.scratch/proactive-mode/issues/09-fgs-start-delay.md`. See `.scratch/proactive-mode/research/`.
- **Compose previews have never been rendered**, any screen, ever - now including `CarProbeScreen`
  and `ExcludedOwnAccountMovementsScreen`. `assets/dtc_descriptions_seed.json` has NEVER existed.
  The 30 voice clips have never been HEARD.
- **`CarDatabaseMigration15To16/17To18/18To19Test` compile but have NEVER RUN.**
  `connectedAndroidTest` UNINSTALLS the app and would take Kevin's real data. The on-device
  migration is the stronger evidence and it WAS performed.
- **`get_monthly_spend` has never been spoken**, so the §4 rule 7 spend disclosure is verified on
  screen only, never aloud.

## In-flight

**THE DISPATCHERS BROKE MEAL LOGGING, FOUND BY KEVIN IN USE 2026-08-17, FIXED AND VERIFIED.**
He asked it to log meals; the UI sat on "Listening..." for ~5 minutes and wrote nothing.
- **`b1868d8` moved `log_meal` behind `ask_body` -> `SubAgent.investigate`**, so a blocking HTTP call
  nested inside another blocking HTTP call. **`ai/SubAgent.kt.postOnce` was not `suspend`**, and
  Kotlin cancellation is cooperative, so **all three timeouts above it were inert** (AgentTool 8s,
  investigate 30s, `handleToolCall` 45s). A timeout is only real if the thing under it suspends.
- **`handleToolCall` was the only `handleEvent` branch that never set a `Phase`**, so every tool call
  rendered as "Listening...". The user-visible bug and the real bug were two separate defects.
- **PROOF the dispatched path had NEVER worked:** `meal_logs` held 7 rows, and the newest predates
  `b1868d8` (that day's dispatcher commit) by 43 minutes. Not one row ever written through `ask_body`.
- Fixed in `18e0582` (real cancellation via `disconnect()` on cancel), `57ed400` (`Phase.THINKING` /
  "Working..." refcounted across concurrent calls, 5 new tests), `170a76c` (`sendToolResponse`
  returns the socket `send()` boolean and surfaces a dropped response).
- **VERIFIED `on-device`** on a hash-verified install: same request re-spoken, rows 8 and 9 written
  in the same second, so two `log_meal` calls in one round both wrote. **`170a76c` is unexercised** -
  it needs a socket death mid-tool-call.
- **The lesson that generalises: `b1868d8` was a cost fix measured only by declaration COUNT.** It
  changed the execution shape of 25 tools and no write path was run before it was trusted.

**HANDS AND SENSES TRIAGED, 2026-08-16 (session 9).** Map `.scratch/hands-and-senses/`. Five of
nine ticket-sized items closed WITHOUT being answered; **only ticket 01 produced code**. Full
account in `library/decisions.md` (2026-08-16) and `library/lessons.md` L24-L28.
- **BUILT + INSTALLED: clear DTCs** (`bd4de4b`). APK hash-verified on the A25 2026-08-16
  (`39d22097...`). **Migration ran clean on the real device: v22, 48 tables, 5/54/2/148/188 rows
  unchanged, integrity ok.** `on-device`: the CLEAR button renders on STORED CODES (conditional on
  codes existing), and **`REFUSED` was produced for real** - correct wording, no confirm button
  offered, and it wrote nothing (`code_clear_events` still 0, `service_records` still 2, so D6
  holds). **STILL NEVER RUN ON A CAR:** `CLEARED`/`RETURNED`/`UNVERIFIED`/`NOTHING_TO_CLEAR`, the
  actual Mode 04 send, the `CLEARED <date>` line, the union rule against a real clear-event, and
  the `clear_codes` VOICE path have none of them been exercised. Migration test still never run.
- **BUILT + INSTALLED: the music fix** (`d683d2c`, `ccef947`), found by Kevin in use. `on-device`:
  banner renders in Setup with correct copy, and its button lands on
  `Settings$NotificationAccessSettingsActivity`. **Kevin granted the access 2026-08-16**, so
  the banner should now be absent and transport should work for everything - neither retested.
- **Still live:** 05 comms (paused mid-ticket), 08 morning brief, 18 inbox, 19 people dates.
- **STANDING RULE from five premise-deaths: grep the premise and confirm the data source before
  spending a session on any ticket** (L25). The map lists what a JARVIS could do, not what Kevin has.
- Three findings that outlived their dead tickets: LEGION **already holds** notification-read access
  via an empty `MediaNotificationListener`; there are **three proactive gates, not one**
  (`AmbientListener`/`TelephonyController` bypass `ProactiveGate`, so the settled master kill switch
  cannot be honoured yet - carried into ticket 21); **78 tool declarations** today.

**QUANT-VIZ + GLANCEABLE, branch `feat/quant-viz` off `feat/car-probe`, 34 commits, suite green.**
Map `.scratch/quant-viz/`, 16 tickets, ALL landed and QA'd on-device with hash-verified installs.
Full account in `library/decisions.md` (2026-08-13/14) and `library/lessons.md` L19.
- **Kevin delegated the taste, then reversed my main call**: "inline viz across all tabs. im not
  gonna read numbers. it has to be glancable." **Every tab face now carries inline viz** - that
  reversal also kills cyberdeck ticket 06's chart-free Today. Treat it as standing.
- Money face: 12-month spend sparkline + daily bars. Today: intake/sleep/cumulative-spend
  sparklines. Fleet: mpg + miles captioned sparklines, due meters. Body unchanged (already wired).
  Drilldowns: category daily bars, monthly spend trend, recap trends, oil-analysis small multiples,
  pantry SPEND panel, goal meters.
- **SET TARGET affordance shipped** (ticket 09) - `set_budget` was voice-only, so no meter could
  ever fill from a screen. Groceries USD 300 written through it on the real phone; meter 69% with
  the pace tick at day 14/31, hand-checked.
- **LOG tab: month calendar** (dots for density, today filled, HIDE collapses) and **tapping a day
  pops an AlertDialog of that day's entries**; `SHOW IN LIST` is now the only route to the day
  filter. Popup renders from the SAME month list that draws the dots, so they cannot disagree.
- **Still not rendered with real data** (nothing to render): pantry chart, goal meter, MISSED's
  4-row cap, the dialog's internal scroll. Verified in code only.
- Deferred nits: month-label formatting duplicated (`SpendTrendDrilldown`/`PantryRows`);
  `dueFraction` treats a month as 30 days.
- **CLAUDE.md §10 "almost all of `ui/` is clean slate" is badly stale (70+ files)** - needs a
  Kevin-visible correction.

**LEDGER: FOUR BUGS FIXED 2026-08-13, ALL FOUND BY PULLING THE DB OFF THE PHONE, NONE BY THE SUITE.**
Full account in `library/decisions.md`. All 497 rows were `Pets` (a SEEDING hole - Room builds a
fresh schema from the entity set and NEVER replays migrations, so the model was starved, not
wrong); `CHECKCARD` read as a merchant (`extractMerchantKey` split on the MMDD date, one rule then
confirmed 48 unrelated rows); transfer detection was never wired to categorisation; ~$24k of own
money counted as spend, now leaving `operating` with the exclusion disclosed in words.

**ANDROID AUTO charted and probed.** Map `.scratch/android-auto/`, 16 tickets, all 6 research
resolved the same day - read the map, not this line, before acting.
- **Settled decision 3 FALSIFIED hours after charting**: the self-managed call is NOT the only route
  to the car's HFP mic (`MODE_IN_COMMUNICATION` + `setCommunicationDevice` gets a plain foreground
  service the same mic). **The risk is DISTRIBUTION, not telephony**; two gates, sideloading and
  **category honesty** (no category fits LEGION) - the second is Kevin's judgement, not a fact.
- Two shipped defects surfaced, both `traced` not `tested`, tickets 13/14/15: **OBD reports the car
  fine when the Bluetooth link goes QUIET** (`Elm327Io` polls `available()`, never blocks on
  `read()`), and the live session could be **silenced with zeroes and no callback**.

**HANDS AND SENSES charted 2026-08-16.** Map `.scratch/hands-and-senses/`, **21 tickets**, from a
competitive-landscape brainstorm (`.scratch/competitive-landscape/research/landscape.md`). Theme:
**LEGION almost only READS; this map gives it hands and new senses.** Destination is DECISIONS.
- **IT IS A SURVEY, NOT A MAP - Kevin caught it the same night.** **Six tickets are efforts in
  disguise** and must each chart their own map before being resolved: home control, wrench mode,
  location intelligence, document vault, memory decay, proactive mode. The map's "Efforts in
  disguise" table names them, why, and the slug. Nine remain genuinely ticket-sized; morning brief
  is borderline. **Do not try to resolve one of the six in a session.**
- **All 4 research resolved same day**, filed to `library/decisions.md`: HA needs only REST (tokens
  are unscoped - use a non-admin HA user); Health Connect sideloads fine but **sync freshness is
  undocumented**; Gemini Live takes **camera frames on a plain key** at 1 fps (2-min/10-min session
  caps make compression + resumption mandatory); the vault needs **no RAG** (0.48 USD/month
  whole-document; context caching is a 15x trap; **free API tier is disqualifying** for private
  docs); **TomTom is the only no-card traffic vendor** and Google retired its 200 USD credit.
- **Three charting corrections, all from grepping**: calendar/Gmail tools, the NHTSA recall checker,
  the `advisor/`+`goals/`+body layers, and the **companion memory system all ALREADY EXIST**.
  Memory's consolidation/reflection ported; **its FORGETTING never did** (nothing consumes
  `lastAccessedAt`, no scorer, no pruning, plus a legacy `MemoryEntry` table). Ticket 20 owns it.
- **Settled by Kevin:** proactivity = master switch + five categories (Safety, Timing, Wellbeing,
  Fleet, Digest), two states each, **master is a true kill switch, nothing exempt**. HA fronts home
  control (never per-device integrations). Glasses are a peripheral, phone stays the brain.
  **People-lookup/OSINT is OUT.** Money is never written to.
- **`is_area_safe` must never ship** - FBI crime data is agency-level and ~13 months stale.

**Still open from 2026-08-07:** `CategoryDao.insert` plus an add-category affordance. D14's fixed
list exists to stop the MODEL inventing categories, not to stop Kevin adding one.

**DRIVE UI charted and largely built, 2026-08-16.** Map `.scratch/drive-ui/`, **7 of 9 tickets
settled**. Full account in `library/decisions.md` (2026-08-16).
- **The screen was rebuilt to Kevin's reference direction** (80s dashboard, "akira, evangelion"):
  segmented columns replace the arc dial, coolant is a COLD-HOT fader, PID codes printed beside
  labels, speed primary over RPM. **Nobody has seen it as designed** - with no dongle every reading
  is stale and correctly renders faint grey; the amber only appears on a live link.
- **~2 Hz is the ceiling and batching is impossible** on a 1998 XJ (ISO 9141-2, not CAN). Ticket 02
  must measure the real round trip before ticket 03 picks a cadence. **The screen makes zero OBD
  calls** - it reads Room; TelemetryRecorder is what polls.
- **mpg is SUPPRESSED everywhere** behind `MpgTrust.SHOW_MPG` until a tank-to-tank fill-up
  calibrates it. It reads ~1.9x high on the Jeep: the formula is faithful, the synthesised MAF
  input is not. `MPG_TRIP` still stores, so a factor applies retroactively.
- **`drives` table now exists (v23).** A dropped OBD link used to skip the tick that finalises a
  drive, so sessions merged - hence one 610-minute "drive" and only one drive ever recorded.

**ALL-EFFORT VERIFICATION SWEEP + BUILD, 2026-08-16 (late).** Five verifiers over every open ticket,
then six builds. **Open tickets 50 -> 24.** Full account in `library/decisions.md`.
- **21 tickets closed as ALREADY BUILT.** All 16 quant-viz (statuses never flipped - the tracker
  advertised 16 phantom tickets), 3 google-account, 2 cyberdeck-ui (superseded). **Fourth, fifth and
  sixth instances of the repo being ahead of its docs.**
- **BUILT AND COMMITTED tonight:** `categoryPending` drift closed at v24 (verified on device); the
  **`remember` leak** (mail could reach permanent memory - now refuses in words); a real
  `responseSchema` for the advisor; the **maintenance due meter restored** (a rebuild had silently
  dropped it); the **trip block wired** to `drives`; and the **import rekey** made set-based.
- **THE IMPORT REKEY HAS NEVER RUN against its own condition** - Kevin's import is latched
  `completed_v3`. Needs a device with that unset.
- **Root cause of the old import hang: `obd_samples` has NO INDEX.** 11,511 per-row statements were
  full table scans over 36,694 rows. **An index on `(vehicleId, pid, timestamp)` is flagged and
  unbuilt** - that table is also range-queried per row by the faults drilldown.
- **Seven orphans found, only one deleted.** `MediaNotificationListener` must exist,
  `embeddingVector` is a Room column, `savePersona`/`assemblePersona` are back-burnered not dead,
  and `BleTransport.closed` was a **missing check** (the BLE half of the quiet-link defect) - now
  wired, not removed.
- **Two shipped features had silently vanished in a screen rebuild** with their pure layers still
  green. Ticket `.scratch/quant-viz/issues/17-silent-regressions.md`. A guard test now exists that
  was **proven to fail** before being trusted.

**PROACTIVE MODE CHARTED, 2026-08-17.** Map `.scratch/proactive-mode/`, **8 tickets**, graduated from
hands-and-senses ticket 21 at Kevin's instruction. Kevin's settled shape carries in as fact: master
plus five categories (Safety/Timing/Wellbeing/Fleet/Digest), two states each, **master is a true kill
switch, nothing exempt**, `CrisisDetector` untouched.
- **TICKET 01 IS FIRST AND BLOCKS THE CATEGORIES.** The kill switch **cannot be honoured today**:
  `AmbientListener` and `TelephonyController` bypass `ProactiveGate` entirely, so a master switch
  would silence 11 of 13 paths and leave two talking.
- **`setMuted` HAS ZERO CALLERS.** Proactive is ON by default and **nothing in the app can turn it
  off.** Not a Settings row, not a voice tool. `ProactivePreferences` is not referenced in `ui/` at all.
- **The five categories exist NOWHERE in code.** Nor does anything bedtime/wellbeing-shaped.
- **Ticket 07 (scheduling) RESOLVED same night.** The threat is **not** the six-hour `dataSync` cap -
  the app targets **SDK 34, not 36** (the A25 *runs* 16; different thing), so that cap and the DND
  lockout are **dormant until someone bumps `targetSdk`**. The real threat is **Samsung's own
  sleeping-apps layer**: unused ~3 days -> restricted bucket, **one alarm/day, no network, while the
  foreground service keeps running and looks fine.** A voice assistant used daily without its UI
  opened is exactly that profile.
- Also found: on Android 16 (applying already at target 34) **an FGS no longer buys unlimited
  WorkManager runtime**; `AriaForegroundService` uses `dataSync` **because it is the only type with no
  runtime prerequisite** and it is the only one with a kill timer, and **no `onTimeout` exists** in
  either service, so the future failure is a fatal `RemoteServiceException`. **No app-only DND bypass
  exists at any importance.**
- **Recommended, NOT acted on:** drop `dataSync` from `AriaForegroundService`; request the
  battery-optimisation allowlist at onboarding; mark LEGION "never sleeping" in One UI.

## Notes for next session

- **The routing bug is FIXED in code, UNVERIFIED on-device.** 2026-08-17 Kevin spoke workout sets;
  nothing was written and he was told it was recorded. Logcat proved `log_workout_set` was never
  called and that the live model routed the request to `ask_goals`, whose domain holds only
  `list_goals`/`ask_advisor`. Three layers landed, weakest first:
  1. `ask_goals`' description no longer claims fitness/planning/car/money territory and now says
     outright that it records nothing; `ask_body` now leads with the driver's own words and claims
     exclusivity over recording a set, meal, sleep, or weigh-in.
  2. Every dispatcher grounding ends in `dispatchBoundaryClause(domain)`, derived from that
     domain's own writable tools, so a zero-write domain is told in words that it cannot record and
     must say so rather than answering around it.
  3. **The mutation gate is now ON for all five dispatchers.** `DISPATCHER_PARAMS` gained an
     optional `intent` enum (`record`/`ask`) the model declares itself, so `wantsWrite(args)` reads
     a stated fact instead of guessing prose - the exact way out the old `requireMutation` doc
     comment named. A `record`-intent call into `goals`/`mail` can never report a mutation, so it
     is REFUSED. Tonight's exact failure is now mechanically impossible, not merely less likely.
  Layer 3 is the only one that holds mechanically; 1 and 2 are prompt text. Six tests in
  `LiveToolboxDispatchRoutingTest`, all green, `reasoned`+`tested` - **nothing here is `on-device`
  yet. Next session: speak a set on the phone and confirm the row lands.**
- **Unrelated pre-existing failure found while running the suite:**
  `BioDigestBuilderTest.bodyweight reports a weekly average, never one line per reading` fails on a
  CLEAN tree (verified by stashing). Expects a 181.0 weekly average, gets per-week lines
  `wk0 180.0lbs wk-1 182.0lbs`. Not caused by the routing fix; not touched by it.
- **Verified tonight `on-device`:** the mic pass (`a28b792` and its seven parents) - 11 captures,
  one under a second, against 4-of-20 sub-second before. Improved, NOT proven fixed; one 559ms
  sample could be a legitimate short turn or the retry firing.
- **Portfolio Phase 1: README rewritten (`0becc7d`), privacy scrubbed at HEAD and through history,
  force-pushed by Kevin and remote verified.** Still to do: `docs/architecture.md`, three case
  studies (the gate catching the interest-row hole, the `b1868d8` dispatcher regression, tonight's
  mic forensics), and a 2-3 minute demo video. **Distribution decided: video is the artifact, repo
  is the depth, APK on request.** Neither a clone nor an APK can demo itself without the reviewer's
  own Gemini key - say so in the README so nobody concludes it is broken.
- **Release APK guard is structural but unconfirmed** (`a4ba553`): the seeding bundle moved to
  `src/debug/assets/`. No signing keys on this machine, so `assembleRelease` cannot run here.
  On the next signed build, run `unzip -l <apk> | grep midnight_import` and expect zero.

- **TRAINING revamp is charted WEEK-FIRST (Kevin, 2026-08-17, chosen against previews):** weekly
  plan-vs-actual meters per lift lead the face, last session's sets below. Run `/wayfinder` for the
  map; it is user-invoke-only, so an agent cannot chart it.
- **The recommendation engine is an EFFORT, deferred (Kevin, 2026-08-17).** His want, verbatim: "a
  daily to do checklist of workouts etc. based on recommendation engine". It is NOT part of the
  TRAINING revamp - it needs its own map (recommendation inputs, how a recommendation is labelled
  so it never reads as fact, and the write path implied by ticking an item off, which collides with
  the read-mostly posture). Do not let it ride along inside a screen ticket.
- **Blocking trap for any per-exercise meter:** logged names are lowercase free text ("leg press"),
  plan names are titlecase ("Leg Press"), and NOTHING joins them today. Measure the matching against
  real rows before designing on top of it.

- **Four bugs this session were found by LOOKING AT THE DATA, none by 955 tests.** Same shape as
  L15: each component individually correct, wrong in aggregate. Pull the DB and query it.
- **A decision put to Kevin twice, with numbers in between, beat the first answer.** He first chose
  to exclude everything the transfer keywords caught; measuring it first showed that also hides
  several thousand dollars across 40 `Zelle payment to <person>` rows of real money. He changed his
  answer.
- **`adb shell cat` CORRUPTS a binary pull** - use `adb exec-out`, and compare the pulled size
  against `ls -l` on the device. **Verify every install by sha256**, never by "Success".
- **Device quirks:** logcat filters the app's own logs (surface diagnostics in the UI); `adb push` to
  `/data/local/tmp` is OEM-blocked, route via `/sdcard`; no `sqlite3` on device - pull the file;
  `pm clear` OEM-blocked; unsigned `.ps1` refused; `uiautomator dump` serves STALE content.
- **Git Bash mangles every device path** (`/data/...` becomes `C:/...Git/data/...`) - export
  `MSYS_NO_PATHCONV=1`. Hit again 2026-08-17 on `run-as cat`; the proactive-mode ticket 07
  resolution already blamed this for the `/data/local/tmp` line above, so treat that line as suspect.
- **Wireless adb does NOT survive a reboot** (Samsung turns the toggle off) and drops on its own.
  When the host lists nothing but the phone says debugging is on, `adb kill-server && adb start-server`
  re-runs mDNS and finds it; `adb reconnect` did not. The port changes every time.
- **Pull the DB WITH its `-wal` and `-shm`** into one directory (three `adb exec-out run-as ... cat`),
  then read it with host `python3`'s `sqlite3` - it replays the WAL, so uncheckpointed writes show up.
  There is no `sqlite3` binary on the host or the device.
- **Real statements: copy in, run, DELETE.** Never commit money data; fixtures are invented.

## Library + how to update this file

`memory/library/` (catalog: `INDEX.md`). Never bulk-read shelves; dispatch the librarian, then
**verify what it writes** - it has invented content before. **Most shelves are FROZEN Midnight AI
history.** LIVE: `decisions.md`, `lessons.md`, `playbook-coding.md` (partly). CLAUDE.md §11.
- Under 80 lines. One-liners; narratives go to the library, then refresh Blocking / In-flight /
  Notes. A decision changing a CLAUDE.md rule is filed to `library/decisions.md` AND applied to
  CLAUDE.md in the same commit; a lesson graduates the same way (L14 -> §4 rule 6).
