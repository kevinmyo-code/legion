---
type: decision
status: open
blocked_by: []
map: backend-erp
---

# The verified ledger history cannot be uploaded, because its anchors were never stored

**Found 2026-08-26 building ledger wave 1 (`0b53bd2`). This blocks the ledger cutover for
essentially all of the real data, and it needs Kevin's ruling.**

## What is wrong

`public.ledger_transactions`' `ledger_txn_header_matches_provenance` CHECK forces every
non-`UNRECONCILED` row to reference a `statements` header, and `statements.ingested_file_id` is
`NOT NULL`. Constructing that header honestly requires the document's real **stated total, opening
balance and closing balance**.

**Those were never persisted.** The gate ran once, inside the parser, at ingestion time, and its
inputs were discarded. `IngestedFile` has no anchor columns, there is no local `statements` table,
and `LedgerTransaction.balanceCents` is null for three of the four parsers.

So the reconcile can upload rule-7 `UNRECONCILED` rows (legal with a null `statement_id` by
construction) and **nothing else**. Every `DETERMINISTIC` and `LLM_RECONCILED` row - the verified
history, the bulk of the ledger - is reported in `Report.skipped` and held out of `isClean`.

Fabricating `opening = 0, closing = sum(lines)` would satisfy the CHECK **by construction**, which
is CLAUDE.md section 4 rule 6's exact failure shape. Not on the table.

## This is ticket 08's defect at a much larger scale

Ticket 08: three pantry receipts unverifiable because the legacy table kept only `totalCents` and
the gate's inputs were never persisted. **Identical root cause here**, across the ledger's whole
verified history instead of three rows.

The lesson those two share, and it should graduate into CLAUDE.md rather than being rediscovered a
third time: **rule 2's guarantee is only as durable as the evidence kept. A gate that passes in
memory and discards its anchors leaves rows nobody can ever re-verify.** The server schema already
gets this right for both aspects; the phone's legacy tables never did.

## Why this one is RECOVERABLE, unlike ticket 08

Ticket 08 was terminal: the receipt photos were destroyed, so re-extraction was impossible.

**Here the source documents are identified and probably still present.** `IngestedFile` stores
`driveFileId`, `treeUri` and `contentSha256` for every file ever ingested. If those statements are
still in the connected Drive folder, **re-ingesting them regenerates the anchors legitimately** -
the deterministic parsers read the printed total and balances straight off the document.

**This creates a sequencing constraint nobody has written down yet.** Ticket 03 ruling 3 retires the
deterministic parsers. If they are retired BEFORE a re-ingestion pass, the only remaining path is
the user's-own-LLM CSV, which means Kevin re-processing every historical statement by hand through
an LLM to recover anchors that a parser could have read for free. **The parsers must not come out
until this is settled.** C4 already gates their removal on the CSV path working; this adds a second
gate.

## The options

1. **Re-ingest the historical statements from Drive before retiring the parsers.** Recovers real,
   falsifiable anchors. Costs a re-ingestion pass and depends on the files still being there -
   which is checkable right now, and should be checked before anything else is decided.
2. **Persist the anchors going forward, and treat existing verified rows as rule-7 provisional.**
   Honest, and cheap to build, but it DOWNGRADES the verified history to unverified. Given the whole
   point of the gate is that verified means something, this trades away the thing being protected.
3. **Relax the server schema for migrated rows** - a nullable `ingested_file_id` plus a marker
   meaning "migrated, header not reconstructible". Cheapest, and it puts a permanent hole in the
   constraint that makes the server's own gate enforceable. Rejected unless 1 and 2 both fail.

**Recommendation: option 1, and check the Drive folder first**, because it is the only option that
ends with the rows being genuinely verified rather than either relabelled or grandfathered.

## A separate consequence, already live and named in code

`account_nickname` for a migrated row is the local `accountId` verbatim; a future real statement
carries Kevin's own typed nickname. Rule-7 supersession keys on `(account_last4, account_nickname)`
TOGETHER, so **a real statement will not supersede the migrated provisional rows it should replace**,
and they will double-count. Stated in `LedgerReconcile`'s own doc comment. Whatever option is chosen
above has to answer this too.
