---
map: aspect-advisors
ticket: 13
title: "Build: goal store and advice log"
type: task
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Build: goal store and advice log

## Question

Implement the two tables decided in [The goal store](02-goal-store.md), Room **v15 -> v16**.

`goals`: id, lineageId, aspect, statement, targetValue?, unit?, metricKey?, deadlineEpoch?,
status (`active`/`achieved`/`abandoned`), supersedesId?, closedAt?, createdAt, updatedAt.
Revision trail: a material change (number, deadline, statement) INSERTS a row sharing
`lineageId` and supersedes the prior; nothing is deleted or overwritten. Read path returns the
current row per lineage. No `TrustTier` column - a goal is an intention, outside both tiers,
matching `BudgetTarget`/`MealTarget`.

`advisor_advice`: id, aspect, questionText, gist, adviceText, proposalJson?, outcome
(`pending`/`accepted`/`rejected`/`expired`), createdAt, resolvedAt?.

DAOs for both. `metricKey` is TEXT with **no CHECK constraint** so widening the metric list is
not a migration - and **confirm that rather than assume it**: read the column's `createSql` in
`app/schemas/` and check the schema JSON is byte-unchanged after a kapt run (CLAUDE.md §5).

Verification: verbatim generated SQL, additive only, `exportSchema = true`, schema JSON
committed, migration test, no destructive fallback. Back up the device DB before any
instrumented run (`adb exec-out`, never `adb shell cat` - MEMORY.md).

## Build report

Built 2026-08-13 (coding agent), **verification re-run independently by the orchestrator** rather
than relayed - MEMORY.md's standing warning after an agent once reported "464/464 green" while
the build was failing.

### Files
New: `data/local/Goal.kt`, `GoalDao.kt`, `AdvisorAdvice.kt`, `AdvisorAdviceDao.kt`;
`app/schemas/.../16.json`; `androidTest/.../CarDatabaseMigration15To16Test.kt` (5 tests);
`test/.../GoalDaoTest.kt` (3), `AdvisorAdviceDaoTest.kt` (2).
Modified: `CarDatabase.kt` (entities, DAOs, `version = 16`, `SCHEMA_VERSION = 16`, migration
registered), `Migrations.kt` (`MIGRATION_15_16`).

### Verification - re-run by the orchestrator, not relayed
| Step | Result |
|---|---|
| `compileDebugKotlin -Pnokey` | BUILD SUCCESSFUL |
| `testDebugUnitTest --rerun-tasks` | BUILD SUCCESSFUL, **764 tests / 0 failures / 0 errors** (summed from JUnit XML, not from the log line) |
| New tests actually ran | `GoalDaoTest` 3/3, `AdvisorAdviceDaoTest` 2/2, from their XML |
| Migration SQL verbatim vs `16.json` | **Matches exactly**, both tables and both `goals` indices, `${TABLE_NAME}` substituted. `advisor_advice` declares no index and the migration creates none - consistent |
| Version bump | `version = 16`, `SCHEMA_VERSION = 16`, `MIGRATION_15_16` in `addMigrations` |
| §5 claim CONFIRMED not assumed | `metricKey` `createSql` is plain `` `metricKey` TEXT `` - **no CHECK constraint**, so widening the metric list is not a migration. Same confirmed for `status`, `outcome`, `unit`, `proposalJson` |

**The agent's own test-count narrative was muddled** ("774 total... actually 3+2=5 new...
764 completed, 3 failed") and was NOT taken at face value. The real number is 764 total including
the 5 new; the 3 failures it mentions were its own test bug (it passed a row `id` where a
`lineageId` was wanted), caught by a real run and fixed before reporting.

### UNMET verification step - named, not buried (CLAUDE.md §8 L11)
**`CarDatabaseMigration15To16Test` has never executed.** `connectedAndroidTest` UNINSTALLS the app
and compiles the working tree, so running it against Kevin's real data was deliberately not done
here. Its shape is a direct copy of the proven `CarDatabaseMigration14To15Test`, so it is
`reasoned` correct, not `tested`. **Follow-up named on [Ship pass](20-ship-pass.md): back up with
`adb exec-out`, install over the top to exercise the REAL migration, then run instrumented
tests.** Do not ship v16 to the device without it.

### Design notes
- `aspect`/`status`/`outcome` are plain `String`, not enums - no consumer exists yet to need
  compile-time exhaustiveness, and it keeps every enum-shaped column here widenable without a
  schema change, consistent with `metricKey`.
- `close()` is the DAO's only in-place mutation, justified in its KDoc: a status flip is not a
  material change to the goal's content, so it does not warrant a new revision row. A reopen or
  restatement still goes through `insert` as a fresh revision.
- `advisor_advice` carries no index. `recent(aspect, limit)` scans, which is correct at personal
  scale and matches the codebase's preference for simple inspectable queries. Revisit only if the
  table grows unexpectedly.
