---
type: build
status: built
blocked_by: []
map: backend-erp
---

# Receipts whose anchors were never stored

**Found 2026-08-26 by the pantry reconcile's gate re-check, on its first run against real data. It
did exactly the job it was added to do.**

## What happened

The pantry reconcile re-checks every stored receipt against its own arithmetic before uploading.
All three of Kevin's receipts failed and were skipped:

| Store | Sum of lines | Printed total | Unexplained |
|---|---|---|---|
| Walmart | 12084 | 12886 | 802 (6.6%) |
| Katy Elyson | 2293 | 2341 | 48 (2.1%) |
| Walmart | 2292 | 2443 | 151 (6.6%) |

## Root cause, traced rather than guessed

Not a bypassed gate, and not a bad engine copy. **The legacy `pantry_receipts` table has exactly
one money column, `totalCents`** - no subtotal, no tax, no other-charges. The engine's `Receipt`
record type has all three, and they are NULL for these rows because there was nothing to copy them
from. The legacy rows fail by identical amounts, so the copy is faithful.

So the agent almost certainly gated these correctly at ingestion, holding tax in memory, and then
persisted only the total and the lines. Two of the three gaps sitting at ~6.6% is Texas sales tax.

**The finding that outlives these three rows: rule 2's guarantee is only as durable as the evidence
kept. A gate that passes in memory and discards its anchors leaves rows nobody can ever re-verify.**
The server schema already gets this right (`receipts.subtotal_cents`/`tax_cents`/
`other_charges_cents`); the phone's legacy table never did.

## Data loss to record honestly

The source photos are gone. `files/pantry_receipts/` was destroyed when a `connectedAndroidTest`
run uninstalled the app, and `DatabaseSnapshot` backs up only the `.db` file - it never covered
photo files. Re-ingestion, which would have recovered the real tax figures, is impossible.

**`DatabaseSnapshot` not covering `files/` is its own gap** and is not limited to pantry. Worth its
own ticket: a backup that restores the database but not the images the database points at leaves
`sourceImagePath` rows pointing at nothing.

## The ruling (Kevin, 2026-08-26)

Ignore or estimate the tax, then reconcile when the bank statement arrives. Implemented as the
honest form of that intent, after the objection below was raised and accepted:

**Storing the gap as tax was rejected.** `tax := total - sum(lines)` makes the anchor true by
construction - rule 6's failure shape - and hides a genuinely missed line item inside a plausible
number, permanently.

**Instead:** `receipts.unaccounted_cents` (migration `20260826000300`, applied). Named for what it
is, never summed into an anchor, and a non-null value forces `provenance = 'UNRECONCILED'` by check
constraint. CLAUDE.md section 4 rule 7 amended to cover LLM-sourced rows whose anchors were never
persisted - narrowly, covering rows already stored and never a new ingestion path.

**The real anchor arrives with the bank statement:** matching the receipt total against a ledger
transaction is external and falsifiable, which the receipt's own arithmetic no longer is.

## What is left to build

1. `PantryReconcile` uploads a non-reconciling receipt as `UNRECONCILED` with `unaccounted_cents`
   set, instead of skipping it. Still reports them separately - they are not ordinary rows.
2. **Every surface rendering one says so in words** (rule 7 condition 3, not negotiable, and never
   by colour or a glyph alone): the pantry screen's receipt section, any spend total that includes
   one, and the migration screen's report.
3. A receipt that still reconciles keeps `provenance = 'LLM_RECONCILED'` and a NULL
   `unaccounted_cents`. Nothing about the healthy path changes.
4. The ledger-match promotion is NOT in this ticket. It needs the ledger cutover first, and it needs
   its own decision about what a match means (same total, near date, right account).

## ALREADY BUILT - found 2026-08-26, the status was stale, not the work

Items 1, 2 and 3 all shipped in `c0101cf` ("Pantry: upload unverified receipts, and fix a
constraint conflict I created"), before this session. **The ticket still said `open`,** so a fully
built feature was sitting on the board as unstarted work. That is CLAUDE.md section 12's named
trap arriving from the other direction: section 12 warns that resolving a decision makes a ticket
vanish while its code is unwritten; this is code written while its ticket still reads unbuilt.
Either way the board lies. Re-checked against the live code rather than the commit message:

- **Item 1** - `PantryReconcile.run` splits three ways (reconciling / shortfall / rejected), uploads
  a shortfall receipt as `UNRECONCILED` with `unaccountedCents = totalCents - itemsTotal`, and keeps
  it in `Report.uploadedUnreconciled`, never folded into `Report.uploaded`. An OVER-accounted receipt
  is rejected outright and never given a fabricated `unaccounted_cents`.
- **Item 2** - `PantryRows.PantryReceiptSection` says the unexplained amount is unexplained and that
  the photo is gone; `PantrySpendPanel` labels any currency total containing one, so an aggregate
  holding an unverified row is itself labelled; `BackendMigrationResolver` words "uploaded as
  unreconciled" distinctly from a dropped row. The wording is unverified/unreconciled throughout,
  never "estimate" - these are amounts the receipt DID state that the app cannot re-verify, which is
  a different claim from a guess.
- **Item 3** - a reconciling receipt still keeps `LLM_RECONCILED` and a NULL `unaccounted_cents`,
  asserted at `PantryControllerBackendTest.kt:254-255`.

`unaccounted_cents` is never summed into an anchor: the residual comes from `totalCents - itemsTotal`
alone, independent of any stored subtotal or tax, so it cannot close its own gate.

**Item 4 (ledger-match promotion) remains genuinely unbuilt and is correctly out of scope** - it
needs the ledger cutover first and its own decision about what a match means (same total, near date,
right account). It is the only path by which these three receipts ever become verified, because the
receipts' own arithmetic no longer can be.
