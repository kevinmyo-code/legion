---
status: accepted
decided: 2026-08-24
decided-by: Kevin
source: "[[decisions#2026-08-24 - The cutover arc: five decisions in one day]]"
tags: [adr]
---

# 37. The aspect engine is the spine

## Standing

ACCEPTED and BUILT, device-verified. `engine/RecordStore.kt` is the single write door for every
domain record in the app; every aspect (fleet, ledger, pantry, notes, places, dates) reads and
writes through it; the widget pager (`ui/widgets/`, `LegionRoute.DASHBOARD`) is `MainActivity`'s
start destination; the per-aspect legacy screens and tables are kept, one tap away behind
"Classic," but hold no write path of their own. Supersedes nothing directly - it is the
architecture the whole `.scratch/aspect-engine/map.md` effort built toward, and no prior ADR
asserted the opposite (per-aspect storage as permanent) strongly enough to need formal
supersession; see the Consequences section for the two ADRs that come closest and were checked.

## Context

LEGION shipped six aspects (fleet, ledger, pantry, notes/lists, places, later dates/goals) each
with its own Room tables, its own controller, its own screen, and its own voice tools - the shape
CLAUDE.md's aspect table originally described. That shape does not scale: every new life domain
needs a full vertical slice (table + DAO + migration + controller + screen + tools) before it can
exist, and the tool surface grows linearly with feature count against a Gemini Live socket that
re-sends its entire tool block every turn (CLAUDE.md's `LiveToolbox` cost note). `.scratch/aspect-
engine/map.md` (23 tickets, resolved 2026-08-23) proposed the alternative: one generic metadata
system - `aspects` / `record_types` / `field_defs` / `records` - the same shape Salesforce uses for
custom objects, arrived at independently. A user can define a new record type by voice; the engine
generates its list/detail/form screens, its widgets, and its CRUD tools from one schema, with no
new Kotlin file per domain.

The open question the map left for this arc was not whether to build it (that was decided and
built in the prior session, all data migrated onto the engine additively, nothing yet reading it)
but **whether to cut existing aspects OVER to it** - retire each domain's bespoke read/write path
in favour of the engine's, one aspect at a time, verified on-device before the next. That is what
2026-08-24 answered: yes, all five flips, in one day, each senior-reviewed and each verified
against the real device database before the next began (`docs/architecture/cutover{1..5}-2026-08-
24.md`). The last flip made the pager the app's front door instead of a feature living behind a
setting.

## Decision

**The aspect engine is the spine, not an alternative path alongside the legacy tables.**
Concretely, in the order they landed:

1. **Cutover 1** - Notes and Places become engine-native (new writes go straight to the engine;
   no legacy-table write path for these two ever existed to retire, since they were mid-build when
   the engine landed).
2. **Cutover 2** - Pantry. Receipt ingestion commits through `RecordStore`, macro estimates and
   all; anchors (the reconciliation gate's stated totals) now persist on the engine record itself.
3. **Cutover 3** - Ledger. `IngestPipeline.commit` writes through `RecordStore` inside one
   `db.withTransaction`; rule 7's provisional-row supersession moved into that same transaction,
   so a commit and its supersession are now atomic together where they were two independent writes
   before.
4. **Cutover 4** - Fleet. Anchors (odometer, service due) derive from a single row rather than
   being independently maintainable in two places, which makes aspect-engine ticket 29's drift
   scenario (two disagreeing anchors) dead by construction rather than merely detected.
5. **Cutover 5** - The home flip. `LegionRoute.DASHBOARD` (the widget pager) becomes
   `MainActivity`'s start destination; `WidgetPagerActivity` (the standalone debug-exported
   Activity it used to be) is deleted and folded in; "Classic" and every per-aspect full-screen
   button keep the old screens one tap away, so no capability was removed, only re-homed.

Legacy tables are **frozen writer-less, not dropped** - kept for one soak period as a rollback
seam and a cross-check, per the same caution that governed each individual wave's migration.
569 active engine records, zero duplicate guids (the `records.guid` unique index added at
`MIGRATION_36_37` for exactly this), Kevin's own verdict on the phone: *"i like it."*

## Consequences

- **`engine/RecordStore.kt` is now the correct place to look for "how does a write actually
  happen," full stop**, for every domain this ADR names. A future contributor reading
  `LedgerController` or `PantryController` and expecting to find `LedgerTransactionDao.insertAll`
  or an equivalent direct table write will not find one; they will find a call into `RecordStore`.
- **The reconciliation gate (CLAUDE.md §4) is unchanged in its rules but changed in its address.**
  Money and other gated values now live inside `EngineRecord.payload` (JSON, typed by
  `field_defs`), not as a dedicated Room column. The gate's exactness requirement does not
  weaken - `PayloadCodec` is the new home of the `Long`-cents discipline [[0007-money-as-long-cents]]
  still names.
- **Two existing ADRs were checked for supersession and found not to need it.**
  [[0007-money-as-long-cents]] is a data-TYPE rule (never `Double`) that holds regardless of
  whether the cents live in a column or a JSON payload field - unaffected, left standing.
  [[0011-ledger-sync-union-and-lww]] describes the legacy `sync/SyncEngine.kt` mechanism over
  `ledger_transactions`/`ingested_files`; that table is now frozen writer-less under this ADR, so
  the ADR's claim is increasingly moot for ledger specifically, but the mechanism itself is
  untouched and still governs whichever legacy tables have not yet been retired - not superseded,
  just narrowing in scope as more tables freeze. The two-phone sync channel going forward is the
  xlsx mirror (`engine/mirror/`, decided the same map, ticket 13) rather than legacy `sync/`; that
  decision does not yet have its own ADR and is not created here, staying out of this ADR's scope.
- **The next named gap is the pager's own**: generated widget screens exist but do not navigate on
  tap yet (zero callers on the generated list/detail/form composables) - `memory/MEMORY.md`'s own
  "known named gap" line.
