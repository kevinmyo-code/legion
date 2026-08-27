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

## DRY RUN RUN ON THE A25, 2026-08-27. All 107 files UNREACHABLE, and the cause is not the files.

Built (`c53b167` and the dry run itself), installed on the A25 (APK sha256 verified against the
build, not trusted to "Success"), and run from Settings > Connections > Ledger re-ingest dry run.

```
Read-only dry run over 107 statement files previously ingested from a connected Drive folder.
Nothing was written.
0 would recover all three anchors and unblock ticket 12 for that file.
107 files no longer reachable through its saved folder link.
Raw rows re-parsed: 0.
```

**The cause is a lapsed permission, not missing files.** `dumpsys activity permissions` shows LEGION
holding **zero persisted URI grants** - verified the command works by confirming it lists 409 lines
of other packages' grants on the same device.

**The `connectedAndroidTest` uninstall of 2026-08-26 claimed a second victim.** Ticket 09 recorded
that it destroyed `files/` and the receipt photos. Persisted SAF grants are per-install too, so they
died in exactly the same way the Keystore key did (which is why the Gemini key had to be re-entered
by hand). Nobody noticed, because nothing reads those grants until something tries to re-open an old
file - and until this ticket, nothing ever did.

**The finding is recoverable and the ledger was never touched.** Re-connecting the Drive folder in
the app should restore the grants; a Drive folder's document id is stable, so the stored `treeUri`
strings should resolve again. If the re-picked tree differs, a fresh folder scan re-discovers the
files anyway and re-ingests them, which recovers the anchors by the other route.

**This is the dry run earning its existence.** Had the re-ingestion been built and run directly, it
would have found the same 107 failures - but against the real ledger, mid-write, with rule-7
supersession and the replace flow live. Instead it cost one read-only pass and changed nothing.

**Still owed before this ticket can close:** re-connect the folder, re-run the dry run, and only then
judge whether the anchors come back. And note what the parser fix already established independently:
**no bank format prints a single combined total**, so even a fully successful re-ingestion recovers
opening and closing balances but never `stated_total`. Two of ruling 4's three anchors, for every
historical statement. That needs its own ruling - see below.

## OPEN QUESTION FOR KEVIN, created by the parser fix

Ruling 4 demands three anchors because an LLM-produced CSV has lines AND total from one
nondeterministic process, so a self-consistent hallucination could satisfy a single check. **That
reasoning does not transfer to a deterministically-parsed bank PDF**, where the lines and the printed
balances come from the document itself and the parser is code, not a model.

The historical statements can supply opening and closing balances, and therefore the
`closing - opening == sum(lines)` check - a real, independent, falsifiable anchor. They cannot supply
a printed total because their banks do not print one.

So: does a DETERMINISTIC statement qualify with two read anchors plus the balance-delta check, or
does it fall to rule 7 provisional for want of a third that its bank never printed? Ruling 4 did not
contemplate this case; it was written about the CSV path.

## RULED 2026-08-27: a DETERMINISTIC statement qualifies on two READ anchors plus the balance delta.

Delegated to me; open to reversal, and this one amends a Kevin ruling so it deserves the most
scrutiny of the four.

**Ruling 4 demanded three anchors for a precisely stated reason:** an LLM-produced CSV has its lines
AND its total from ONE nondeterministic process, so a self-consistent hallucination could satisfy a
single-anchor check - section 4 rule 6's failure shape in a new place. Three separately printed
numbers force the model to be consistently wrong three times.

**That reasoning does not transfer to a deterministically parsed bank PDF, and the parser fix proved
the case is unavoidable rather than merely inconvenient.** No bank format prints a single combined
total - DBS prints separate withdrawal and deposit totals, BofA prints per-section figures. So the
third anchor does not exist to be read, for any statement Kevin owns or will own.

The risk profile is also different in kind. The lines and the printed balances come off the document
by code, with no model anywhere in the path. `closing - opening == sum(lines)` is a real, external,
falsifiable check against two numbers the BANK printed - not the document agreeing with itself
through one nondeterministic step. Section 4 rule 1's "deterministic first" preference exists
precisely because that path is more trustworthy, and it would be perverse to hold it to a bar only
the less trustworthy path can clear.

**So:** a statement whose extraction is DETERMINISTIC, which yields a read opening balance, a read
closing balance, and satisfies `closing - opening == sum(lines)`, commits as `DETERMINISTIC`.
`stated_total_cents` is genuinely NULL and is recorded as such rather than synthesised - a parser
must never return `sum(lines)` as the stated total, which would make the check an identity.

**Unchanged, and these are the load-bearing halves:**
- **The LLM CSV path still requires all three.** Ruling 4 stands exactly as written for the path it
  was written about. This amendment is scoped to deterministic extraction and nothing else.
- **Two anchors is the floor, not a discount.** A deterministic statement yielding fewer than two
  read anchors still falls to rule 7 provisional. `BofaCardCsvStatementParser` prints nothing and
  stays provisional, permanently and correctly.
- Rules 2 through 7 are untouched.

**Server consequence:** `commit_statement` currently requires all three. It needs a deterministic
branch accepting two plus the delta check, and `statements.stated_total_cents` must become nullable.
The gate corpus gains cases for the new branch - a passing two-anchor deterministic statement AND one
whose delta check fails - so the two implementations stay proven to agree rather than merely both
existing.

**This is what unblocks the ledger cutover**, and with it the last aspect in phase 4.
