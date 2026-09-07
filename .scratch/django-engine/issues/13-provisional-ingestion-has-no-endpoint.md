---
map: django-engine
ticket: "13"
title: "Rule 7 provisional ingestion has no endpoint, and the commit paths structurally cannot be one"
type: decision
status: open
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Rule 7 provisional ingestion has no endpoint

Found 2026-09-07 while porting the gate ([[03-the-gate-in-python]]). The ticket's dispatch brief
asked for a test proving "a document stating no anchor is stored `UNRECONCILED` and the response
says so in words". That test cannot pass, and the reason is not a gap in the port.

## The evidence

Read off the live schema through `pg_constraint`, not inferred from the migrations:

| Constraint | Definition | Consequence |
|---|---|---|
| `statements_not_provisional` | `CHECK (provenance <> 'UNRECONCILED')` | a statement header can never be provisional |
| `ledger_txn_header_matches_provenance` | `CHECK ((provenance = 'UNRECONCILED' AND statement_id IS NULL) OR (provenance <> 'UNRECONCILED' AND statement_id IS NOT NULL))` | a provisional line must have **no header at all** |
| `receipts_not_provisional` | `CHECK (provenance <> 'UNRECONCILED' OR unaccounted_cents IS NOT NULL)` | a provisional receipt must carry an unexplained amount |

`public.statements`' own table comment says the first two out loud: *"A provisional (rule 7) import
has NO row here, which is why `ledger_transactions.statement_id` is nullable."* That nullability is
the schema saying, in the only language it has, that a provisional row was never checked against
anything.

And `commit_receipt` never writes `unaccounted_cents` - the column is not in its insert list - so
the third constraint is unreachable from that path too. Both commit RPCs, and now both
`/api/ingest/*` endpoints, are structurally incapable of storing a provisional row. That is
correct behaviour, not a bug: **a commit path is for documents that passed a gate.**

## So where do provisional rows come from today

Two places, neither of them an ingestion endpoint:

- **Headerless `ledger_transactions`**, written directly. `LedgerController.logPendingTransaction`
  (a voice-logged charge, never a file) is the live producer. There is an index built for exactly
  this shape: `ledger_transactions_provisional_idx ... where provenance = 'UNRECONCILED'`.
- **`uploadMigratedReceipt`**, which is rule 7's 2026-08-26 amendment: receipts already extracted
  and stored, whose gate inputs were never persisted, carried up with `unaccounted_cents` set.
  That amendment covers rows already extracted, **never a new ingestion path**.

Rule 7's original case - Bank of America's mid-cycle card CSV, which prints no total, no opening
and no closing - has no server-side path at all. It had one on the phone
(`.scratch/ledger-drive-ingestion/issues/12-provisional-card-csv.md`), and statement ingestion then
left the phone entirely (backend-erp ticket 25), so the capability left with it.

## The decision this ticket wants

**Does the engine get a provisional ingestion path, and if so, whose?** Three shapes, and the
choice is Kevin's:

1. **No.** Rule 7 ingestion is retired along with the phone's statement importer. A no-anchor
   document is quarantined with a sentence and the user is told to find a document that states
   one. Cheapest, and loses the mid-cycle CSV.
2. **`POST /api/ingest/provisional`**, a third endpoint under the same mount. It would have to
   enforce all four of rule 7's conditions, and condition 1 is the sharp one: **extraction must be
   DETERMINISTIC**, because an LLM adds cost and nondeterminism to rows that are already
   unverifiable and cannot manufacture an anchor. That collides with the 2026-08-25 amendment
   retiring the statement parsers - so this option needs its own answer to "deterministic by what,
   server-side, with no PdfBox".
3. **The web app's import screen**, with the provisional rows written through a domain endpoint
   rather than an ingestion one, on the grounds that a provisional row is authored data with a
   warning rather than a gated fact.

## What binds whichever is chosen

Rule 7's four conditions, all load-bearing together, none optional: deterministic extraction, every
row tagged `UNRECONCILED`, **every surface that renders one says so in words** (never by colour or
a glyph alone), and the rows are transient - superseded and deleted when a gated file commits over
the same account and window.

The supersession half is already built and tested on the server:
`test_a_gated_statement_supersedes_provisional_rows_in_its_window`. It is the producer that is
missing, not the cleanup.

## One line to fix in the meantime, in either direction

`ingest/views.py`'s `PROVISIONAL_REFUSAL` names this ticket by path. If this ticket resolves to
option 1, that sentence should stop pointing at a ticket and start stating the rule.
