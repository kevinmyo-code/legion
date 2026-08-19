---
map: ledger-drive-ingestion
ticket: 13
title: "`categoryPending`'s default has drifted: a fresh install and a migrated one differ"
type: task
status: resolved
status-detail: "built in 703778a at v23->v24; drift audit re-run 2026-08-18, 0 drifted columns"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# `categoryPending`'s default has drifted: a fresh install and a migrated one differ

## Question

`ledger_transactions.categoryPending` is declared on the entity with **no default**, but every
migrated device carries **`DEFAULT 0`**. So the same column has two different DDL definitions
depending on how the database came into existence.

| | |
|---|---|
| Entity (`LedgerTransaction.kt:97`) | `val categoryPending: Boolean = false` - **no `@ColumnInfo(defaultValue = ...)`** |
| Migration (`Migrations.kt:176`) | `ALTER TABLE ... ADD COLUMN \`categoryPending\` INTEGER NOT NULL DEFAULT 0` |
| Schema JSON, **v12 through v20** | `defaultValue: null` |
| Kevin's real device | `categoryPending INTEGER NOT NULL DEFAULT 0` |

The `DEFAULT 0` in the migration is not optional - SQLite requires a default when adding a
`NOT NULL` column to a table with existing rows. The omission is on the entity side: nobody added
the matching `@ColumnInfo`.

**Consequence:** a fresh install creates the column with no default clause; an upgraded install has
one. They will never converge. CLAUDE.md §5's standing requirement is the opposite - the codebase
says it repeatedly, e.g. `Vehicle.archived`'s doc: *"DEFAULT '0' mirrors the migration so a migrated
row validates identically to a fresh one."* Several columns do this correctly (`voiceName`,
`personaTraits`, `trim`, `confirmed`, `updatedAt`, `archived`, `neverDone`). This one does not.

## How it was found, and the scope

Found on 2026-08-15 while verifying the fleet-maintenance map's **v19 -> v20** migration against a
copy of Kevin's real database. The migration itself was clean; a full `PRAGMA table_info` comparison
of all 44 entities against the generated `20.json` flagged this as the only genuine difference.

**A follow-up audit of every default across all 44 entities found exactly one drifted column - this
one.** So the problem is contained and this ticket is the whole of it.

## Why it has not bitten anything

Kevin's app opens at v19 today and runs, so Room is tolerating it in practice. Room's `TableInfo`
comparison treats an expected `defaultValue` of null as "don't care" rather than "must have no
default", which is why an extra `DEFAULT` on disk passes validation.

**That is a reason it is low-priority, not a reason it is fine.** The tolerance is Room's
implementation detail, not a guarantee, and the two-shapes-for-one-column state is exactly what §5's
mirroring rule exists to prevent.

## What to do

1. Add `@ColumnInfo(defaultValue = "0")` to `LedgerTransaction.categoryPending`, matching the
   migration and matching the seven columns elsewhere in this codebase that already do it.
2. **That changes the schema identity hash**, so it needs a version bump even though no SQL changes
   on a migrated device - the same shape as `MIGRATION_16_17`/`17_18`, whose own docs say the bump
   exists *"only because Room requires one to run anything at all."* The migration body is a no-op
   for already-migrated devices; a fresh install simply gets the correct DDL from the start.
3. Confirm afterwards the way §5 says to: read the column's `createSql` in `app/schemas/`, and check
   the new schema JSON is byte-consistent after a kapt run.
4. **Re-run the drift audit** across all entities after the bump, so this closes with evidence
   rather than intent.

## Watch for

Do **not** try to "fix" this by dropping the `DEFAULT 0` from the historical migration at
`Migrations.kt:176`. CLAUDE.md §5 and this repo's own precedent
(`MIGRATION_17_18`'s frozen-list comment) are explicit: **a migration must keep producing exactly
what it always produced.** The entity is what changes, never the shipped migration.

## Assumptions ledger

- `traced`: the entity declaration, the migration's `ADD COLUMN` text, and `defaultValue: null` in
  every schema JSON from v12 to v20.
- `on-device`: `categoryPending INTEGER NOT NULL DEFAULT 0` read from a copy of Kevin's real
  database, 2026-08-15.
- `on-device`: the audit result - **1 drifted column out of 44 entities**.
- `reasoned`: that Room tolerates an unexpected on-disk default because expected-null is treated as
  "don't care". Inferred from the app running at v19 with this drift present, not from reading
  Room's source.

## Verification 2026-08-16 - NOT BUILT. The drift is LIVE at v23.

Re-checked because this ticket is old enough to have gone stale. **It has not.** All `traced`.

- `data/local/LedgerTransaction.kt:97` - `val categoryPending: Boolean = false`, **still with no
  `@ColumnInfo(defaultValue = ...)`**. The same line number the ticket cited.
- The shipped migration is correctly untouched: `Migrations.kt:176` still adds the column with
  `INTEGER NOT NULL DEFAULT 0`.
- **The drift has now propagated through three further schema versions.** The database is **v23**,
  not the v20 this ticket was written against. In `app/schemas/.../23.json` the column's `createSql`
  carries **no DEFAULT**, and its field object carries **no `defaultValue` key at all**.
- The house pattern it points at is still followed everywhere else - `@ColumnInfo(defaultValue = ...)`
  on `CarTask.kt:31,33,39`, `Goal.kt:90,91`, `DriveReassignment.kt:35,45`, `ChassisQuirk.kt:38`.

**Not dead code:** `categoryPendingRows()` (`LedgerTransactionDao.kt:351`) feeds
`LedgerController.kt:744` and the CATEGORIZE drilldown (`LedgerScreen.kt:548`). Five writers and
eight readers, all live.

## RESOLVED 2026-08-18 - built, and the board was stale

Re-verified rather than rebuilt: the fix had already landed in `703778a` ("Close the
categoryPending drift, three schema versions late") and this ticket simply never had its status
changed. Every gate the "What to do" section names was checked against the tree:

- `data/local/LedgerTransaction.kt:98-103` carries `@ColumnInfo(defaultValue = "0")` with a doc
  comment in `Vehicle.archived`'s style. `traced`
- `MIGRATION_23_24` (`data/local/Migrations.kt:1008-1035`) is the empty-bodied version bump, doc
  comment following `MIGRATION_16_17`/`17_18`, registered at `CarDatabase.kt:324`. The database is
  at v25; two unrelated migrations have shipped on top since. `traced`
- The shipped `MIGRATION_5_6` at `Migrations.kt:176` is untouched, confirmed by `git log` on the
  ticket's own commit. `traced`
- `app/schemas/` v24 and v25 both carry `` `categoryPending` INTEGER NOT NULL DEFAULT 0 `` in
  `createSql` and `"defaultValue": "0"` on the field; v23 carries neither, as expected. `traced`
- `git diff --stat app/schemas` after a fresh kapt run is empty - schema JSON regenerates
  byte-identical. `built`
- **Drift audit re-run across all 46 entities, 443 fields**: one apparent mismatch,
  `maintenance_items.intervalSource` (entity `"SEEDED"` vs schema `"'SEEDED'"`), traced to Room
  quoting a TEXT literal when it serializes schema JSON - `createSql` matches the shipped migration
  at `Migrations.kt:904` exactly. **True drift: 0.** `built` for the audit run, `reasoned` for the
  quoting explanation.
- Migration test `app/src/androidTest/.../CarDatabaseMigration23To24Test.kt` already exists. It is
  an instrumented test, so `testDebugUnitTest` does not cover it - **not run on a device here.**
  `traced`

`./gradlew compileDebugKotlin -Pnokey` green. `testDebugUnitTest` had two failures unrelated to
this ticket (`BioDigestBuilderTest`, `ProactiveBusTest`), both pre-existing on the branch.

