---
map: ledger-drive-ingestion
ticket: 04
title: "What makes two identical transaction lines distinct?"
type: grilling
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# What makes two identical transaction lines distinct?

## Question

`LedgerController.isDuplicate` matches on `accountId + txnDate + amountCents + description`. Two
genuinely separate five-dollar coffees at the same shop on the same day collapse into one, and the
second is silently dropped. Kevin's call is to fix this properly rather than live with it.

`LedgerTransaction` already carries `lineRef` and `sourceFile`, so the raw material may exist.
Decide:

1. **What is in `lineRef` today?** Read both parsers. Is it a stable per-statement line index, a
   raw text fragment, or something re-derived on each parse? Its stability across a re-parse of the
   same file determines whether it can carry dedup weight at all.
2. **The correct dedup key.** Probably account plus date plus amount plus description plus an
   occurrence ordinal within the source statement. Confirm that survives the real case this must
   handle: the same transaction appearing in two overlapping statements from the same account.
3. **Overlapping statements.** Two statements covering overlapping date ranges genuinely restate
   the same transactions. That is the case per-transaction dedup exists for, and it directly
   conflicts with preserving twins. Resolve the conflict explicitly.
4. **Existing rows.** Any already-committed data was written under the old key. Does the migration
   need to do anything, or is the installed base zero? (It is almost certainly zero, but say so.)
5. **Tests.** This is exactly the class of bug that passes a compile and fails on real money.
   Specify the test cases, including the twin case and the overlapping-statement case.

Note the failure mode runs both ways: too strict silently drops real transactions, too loose
silently double-counts them. Both corrupt the balance. State which way this errs and why.

---

## Resolution (2026-08-02, Kevin, 4 calls)

### 1. What is in `lineRef` today - answered as FACT, read from all three producers

| Producer | `lineRef` | Stable across a re-parse of the same file? | Unique per physical line? |
|---|---|---|---|
| `BofaStatementParser.kt:128` | `"$fileName:'${line.take(60)}'"` | **Yes**, deterministic text | **NO.** Two identical coffee lines produce an identical `lineRef` |
| `DbsStatementParser.kt:158` | `"$fileName:p$pageIdx:${"%.1f".format(line[0].y)}"` | **Yes**, deterministic geometry | **Yes.** Two lines cannot share a y on one page |
| `LedgerStatementAgent.kt:131` | `"$fileName:llm:$i"` | **NO.** `i` indexes a nondeterministic LLM response | Yes, within a single parse |

**Conclusion: `lineRef` cannot carry dedup weight.** No property holds across all three producers, and
the one producer whose `lineRef` *is* unique (DBS) is the one that already has balance continuity to
lean on. Any design keyed on `lineRef` would work for DBS, silently fail for BofA, and be undefined
for the LLM path.

Note also `BofaStatementParser` appends continuation lines into `description` **after** `lineRef` is
built, so `lineRef` reflects only the first physical line while `description` may not.

### 2. The dedup key: count per tuple, not boolean existence

`LedgerTransactionDao.countMatching` asks "does a matching row exist". **Replace that with "how
many".**

```
key = (accountId, txnDate, amountCents, normalizedDescription)

N = count of that key in the INCOMING statement
M = count of that key in EXISTING rows
insert the first max(0, N - M) of the incoming group
```

Why this and not an occurrence ordinal: it needs no new column on `ledger_transactions`, no
migration beyond what 03 already specifies, and **no `lineRef` stability at all**, so it behaves
identically for the deterministic parsers and the nondeterministic LLM path. An ordinal column would
be functionally equivalent for the cases that matter while storing a value only meaningful relative
to one statement's contents.

### 3. Overlapping statements - the conflict resolved

This is the case per-transaction dedup exists for, and counting resolves it without special-casing:

| Case | N | M | Inserted |
|---|---|---|---|
| Two identical coffees, one statement | 2 | 0 | **2** (the bug this ticket exists for) |
| Same statement imported twice | 2 | 2 | 0 |
| Monthly then YTD, both restate both coffees | 2 | 2 | 0 |
| Bank adds a third on a corrected statement | 3 | 2 | 1 |

### Normalization, and where the comparison runs

Comparison-time only. The stored `description` is **never** modified; it stays exactly as printed,
for display and for audit.

```kotlin
private fun dedupKey(t: LedgerTransaction) = DedupKey(
    t.accountId,
    t.txnDate,
    t.amountCents,
    t.description.trim().replace(Regex("\s+"), " ").uppercase(),
)
```

Fetch existing rows for the account across the incoming statement's date range, then group and count
in **Kotlin**, not SQL. That avoids a stored normalized column and its migration, keeps the rule in
one unit-testable function, and statements are small enough that the ranged fetch is cheap.

This closes the most likely double-count cause: two exports of the same transaction differing only
in whitespace, case or spacing. `BofaStatementParser`'s continuation-line appending makes that
materially likely rather than theoretical.

### 4. The replace hole (found during grilling, not present in the ticket)

**The problem.** Counting means an overlapping statement can contribute **zero rows**. So a
transaction attested to by both a monthly and a YTD statement exists in the database under only the
monthly's `sourceFileId`. Ticket 03's replace flow runs
`DELETE FROM ledger_transactions WHERE sourceFileId = :id`, and those transactions vanish - even
though the YTD statement also attested to them, and it is already `INGESTED` so a rescan skips it.
Silent financial data loss, exactly the failure mode CLAUDE.md §4 exists to prevent.

**The fix.** On any deletion of a file's rows for replacement:

```
DELETE FROM ledger_transactions WHERE sourceFileId = :fileId
UPDATE ingested_files SET state = 'NEW'
  WHERE accountId = :accountId
    AND driveFileId != :fileId
    AND state = 'INGESTED'
    AND minTxnDate <= :replacedMax AND maxTxnDate >= :replacedMin
-- then re-parse and insert the replacement; the next scan re-ingests the reset files
```

Self-healing, needs no attestation table, and the cost is bounded to genuinely overlapping
statements rather than the whole account. Requires **amendment 2 to ticket 03** (below).

### 5. Which way it errs, stated explicitly as the ticket demands

**It errs toward DROPPING.** Two genuinely separate but identical purchases that straddle two
statements for the same date are treated as one transaction and one is lost.

That is the right default because the two situations are not equally likely: an overlapping
monthly-and-YTD pair is routine and expected, while two truly separate identical purchases landing
in different statements for the same date is rare. The data genuinely cannot distinguish them, so
this is a choice about which rare case to accept, not a solvable problem.

**It is recorded rather than silent.** `ingested_files.duplicatesSkipped` counts every skip per file,
so the behaviour is auditable after the fact ("3 lines matched existing rows") instead of invisible.

### 6. Existing rows - answered as FACT

**The installed base is zero.** The app has never run on a device, the database is at v3, nothing is
released, and there are no users. No rows were written under the old key, so the migration has
nothing to do about them and no backfill is required.

### 7. Test cases

This is the class of bug that compiles clean and fails on real money. All of these are unit tests
against the dedup function and DAO, not integration tests.

| # | Case | Expect |
|---|---|---|
| 1 | Two identical lines in one statement | **Both inserted.** The bug this ticket exists for |
| 2 | Same statement imported twice | Zero inserted the second time |
| 3 | Monthly then YTD, full overlap | Zero inserted from the YTD |
| 4 | Monthly has 2 of a tuple, YTD has 3 | Exactly 1 inserted |
| 5 | Description differs only in whitespace and case | Treated as the same, zero inserted |
| 6 | Same date and amount, different merchant | Both inserted; description separates them |
| 7 | Replace a file overlapping another `INGESTED` file | Overlapping file resets to `NEW`; rows restored on rescan |
| 8 | `duplicatesSkipped` after a partial-overlap import | Equals the actual number skipped |

Cases 1, 3 and 7 are the ones that fail on real money. **Case 7 did not exist before this session** -
it is the replace hole, which was found by grilling and not by reading the code.

### Amendment 2 to ticket 03 (required by this ticket)

Four columns added to `ingested_files`: `accountId` (nullable - only known after parsing),
`minTxnDate`, `maxTxnDate`, `duplicatesSkipped`. Cheap because none of 03 is implemented yet.

### What this ticket does NOT settle

- `accountId` derivation itself, for a mixed-institution folder. Still open on the map.
- The quarantine review UX that would surface `duplicatesSkipped`. Still open.
