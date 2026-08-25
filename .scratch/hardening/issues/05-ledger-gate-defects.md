---
map: hardening
ticket: "05"
title: "Three ledger gate defects found while grounding backend-erp ticket 03"
type: task
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Three ledger gate defects found while grounding backend-erp ticket 03

## Question

All three predate the 2026-08-25 backend-ERP grilling and none was introduced by it. They were
surfaced by a read of the ingestion path done to decide where the gate lives once truth is remote
(`.scratch/backend-erp/issues/03-the-gate-server-side.md`), and are filed here so they are not lost
in a decisions ticket. Tags are the scout's own: `traced` means read in the code, `reasoned` means
inferred from the code and not reproduced.

### 1. Rule 7's test suite has been testing dead code since cutover 3 (`traced`) - the sharpest

**RESOLVED 2026-08-25.** Ported to `app/src/test/.../IngestPipelineProvisionalSupersedeTest.kt`
(JVM/Robolectric), calling the REAL `IngestPipeline.commit` and asserting against engine records.
The androidTest file is deleted. Rule 7 now has **5** live tests through the real entry point (the
pre-existing `IngestPipelineEngineCommitTest.kt:136` plus the 4 ported). Suite verified green
independently from the JUnit XML: 2,549 tests, 0 failures, 0 errors, 0 skipped (2,543 at the time
of the port; 2,549 after Phase 0's ScheduledBackup tests landed alongside it).
**The old file's stated reason for not calling `commit` was obsolete**, not a real constraint -
`RoomTestReset.resetCarDatabaseSingleton()` already solved the singleton problem and the live
engine test had been using it since cutover 3.

`androidTest/.../ledger/IngestPipelineProvisionalSupersedeTest` has four cases, including the one
that asserts the ordering bug directly (`reconciledRowsAreNotDroppedAsDuplicatesOfTheProvisionalRowsTheyReplace`,
`:178`). **None of them calls `IngestPipeline.commit`.** They re-implement the sequence in a local
`commitLikeIngestPipeline` helper (`:102-142`) against `LedgerTransactionDao.deleteSupersededProvisional`
and `insertAll` - methods that `ledger/IngestPipeline.kt:35-37` itself declares dead post-cutover-3.
The stated reason for the copy (`:26-37`) is real: `CarDatabase.getDatabase`'s process-wide singleton
made calling the real entry point awkward.

Live engine-path coverage of rule 7 is therefore **one test**,
`test/.../ledger/IngestPipelineEngineCommitTest.kt:136`. Rule 7 is the behaviour that decides
whether an unverified row can outlive the verified one that supersedes it, and it looked like the
best-covered thing in the ledger.

**Fix:** point the four cases at the real `IngestPipeline.commit`, or delete them as superseded by
`IngestPipelineEngineCommitTest` and say so in writing. Do not leave them looking like coverage.
**Do this before ticket 03's RPC work starts** - rule 7 is being reimplemented in SQL, and
reimplementing a behaviour whose tests do not run against production code is how it silently
changes.

### 2. A schema mismatch strands a file with no compensation (`reasoned`, confirm first)

`IngestPipeline.commit` reads its schema OUTSIDE the transaction (`:319-321`), then calls
`fieldIds.getValue(...)` inside it (`:335`, `:373-374`). `Map.getValue` throws
`NoSuchElementException`, but the `catch` at `:431` is narrowed to `EngineWriteFailedException`, so
the exception escapes uncaught and the compensating write back to `IngestState.NEW` (`:437-442`)
never runs. The file is left at whatever `stage` last wrote and is not re-offered.

The general rule, which is the part worth keeping: **a narrow catch around a compensating action
must cover every exception the guarded block can actually raise**, or the compensation is
conditional on the failure being the expected kind. Today the window is microseconds; a remote
schema read widens it to a network round trip, which is why ticket 03 surfaced it.

**Fix:** confirm the escape with a test that corrupts a field def, then either widen the catch or
move the schema read inside the transaction. Confirm before fixing - this is `reasoned`, not
observed.

### 3. `BofaStatementParser` has no explicit non-empty guard (`reasoned`)

A genuinely zero-movement statement could pass with zero rows: the balance-continuity check
(`beginningBalance + net != endingBalance`) is satisfied by nothing at all when nothing moved. This
is exactly the vacuous-pass class CLAUDE.md §4 rule 6 was written for, and
`BofaCardStatementParser.parseSectionBody:331-346` already closes it for the card parser after it
bit once. `LedgerStatementAgent.kt:208-210` and `PantryReceiptAgent.kt:224-226` both guard
explicitly; this parser does not.

The rule worth carrying: **rule 6's guard has to be re-applied per extractor.** Each one implements
the gate arithmetic separately - there is no shared gate object
(`engine/ReconciliationGate.kt` has zero production implementations) - so fixing one parser does
nothing for its siblings.

**Fix:** add the guard, or note that backend-erp ticket 03 ruling 3 retires this parser entirely
and close this as obsolete once that lands. **If the retirement slips, the gap is real** - do not
close it on the strength of a plan.

## Verification

- [x] Defect 1 (done 2026-08-25): the four cases either call `IngestPipeline.commit` or are gone, and rule 7's live
      coverage is stated in the ticket that closes this.
- [ ] Defect 2: a test proves the escape before any fix is written; after the fix, a corrupted
      field def leaves the file at `IngestState.NEW`.
- [ ] Defect 3: either a guard plus a zero-movement fixture that quarantines, or a written note
      that the parser is retired and by which commit.
- [ ] `testDebugUnitTest` green.
