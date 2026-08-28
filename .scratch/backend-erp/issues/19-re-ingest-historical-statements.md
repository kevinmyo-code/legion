---
type: build
status: open
blocked_by: []
map: backend-erp
---

# Re-ingest the historical statements, so the verified ledger history can be uploaded

**Left behind by ticket 12's resolution on 2026-08-28, per CLAUDE.md section 12: a resolved
decision ticket creates its build ticket in the same commit, or a fully-decided and entirely
unbuilt feature vanishes from the board and reads as finished work.**

Ticket 12 chose option 1 - re-ingest the historical statements from Drive so the deterministic
parsers read the anchors off the documents again, legitimately. Everything about that decision is
made. **None of the re-ingestion is built.**

## What exists already

- **The read-only dry run**, built and run on the A25 2026-08-27 (`c53b167`). It enumerates every
  `IngestedFile`, tries to re-open each through its saved `treeUri`, and reports what would be
  recovered. It writes nothing.
- **The two-anchor deterministic branch of `commit_statement`**, applied to `HomeERPBackend` and
  proven by the 17-case corpus. A re-ingested statement that yields a read opening balance, a read
  closing balance and a passing `closing - opening == sum(lines)` commits as `DETERMINISTIC` with a
  NULL `stated_total_cents`. That is what makes this ticket worth doing at all.

## The device half, and it must happen first

The dry run found **107 of 107 files unreachable**, and the cause is not the files - LEGION holds
**zero persisted SAF URI grants**, destroyed by the `connectedAndroidTest` uninstall of 2026-08-26
(the same event that took `files/`, the receipt photos and the Keystore key).

1. Re-connect the Drive statement folder in the app, which re-grants the persisted URI permission.
2. Re-run the dry run.
3. Only then read the number. If files resolve, this ticket is a build. If they do not, the ticket
   changes shape and ticket 12's option 2 comes back onto the table.

**Nobody should build the write path before step 3 reports a non-zero recoverable count.** That is
the whole reason the dry run was built read-only, and it already earned its existence once.

## What to build, once the dry run comes back non-zero

- A real re-ingestion pass over the recoverable files, writing through the SAME `IngestPipeline`
  path a fresh file takes - never a bespoke shortcut that skips the gate.
- Anchor persistence, so this cannot recur: the gate's inputs (opening, closing, stated total when
  the bank prints one) are stored, not just its verdict. This is CLAUDE.md section 4 rule 8, added
  by ticket 12's closure.
- `LedgerReconcile` then uploads `DETERMINISTIC` rows instead of reporting them in `Report.skipped`,
  and `isClean` stops excluding them.

## The gate this ticket holds, and it is real

**The deterministic statement parsers must NOT be retired until this closes.** Ticket 03 ruling 3
retires them; ticket 12 added this second gate on top of C4's. Retiring them first means every
historical statement has to be re-processed by hand through an LLM to recover anchors a parser
reads for free.

## Not blocked on anything in code

`blocked_by` is empty deliberately: nothing in the repo blocks this. It waits on a human with the
phone, which the board has no way to express.

## One adjacent gap, noted here rather than lost

**`LedgerReconcile` has no hands path.** `BackendMigrationScreen` offers places, pantry, events and
fleet; ledger is absent, so the one reconcile that would upload the provisional rows cannot be run
from the app at all - the same "built, tested, and unreachable" defect that screen's own doc comment
was written to fix for the other three. It is left out of this commit deliberately rather than
bolted on: the row is only worth adding once this ticket's re-ingestion decides what `LedgerReconcile`
actually uploads, since today it can upload nothing but `UNRECONCILED` rows.
