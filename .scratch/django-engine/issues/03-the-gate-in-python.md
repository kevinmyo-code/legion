---
map: django-engine
ticket: "03"
title: "The section 4 gate in Python: same payload, same verdicts, same corpus"
type: build
status: open
blockers: ["02"]
blocked-by: ["[[02-models-column-exact]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# The gate in Python

`public.commit_statement(payload jsonb)` and `public.commit_receipt(payload jsonb)` become
`POST /api/ingest/statement` and `POST /api/ingest/receipt`. **The request body and the response
body are the RPC's `payload` and return, unchanged**, so `SupabaseLedgerBackend` and
`SupabasePantryBackend`'s DTOs survive into ticket 09 with only the transport swapped.

## Shape

- `server/ingest/gate.py`: pure functions, no ORM. `check_statement(lines, stated_total, opening,
  closing) -> Verdict`, `check_receipt(...) -> Verdict`. Rule 6 (empty extraction never passes),
  rule 2 (exact equality, `int` cents), the three-anchor requirement for `LLM_RECONCILED`, the
  two-anchor deterministic path from migration `20260827000300`. Every branch of the plpgsql has a
  Python branch with the SQL line quoted in a comment.
- `server/ingest/views.py`: one `transaction.atomic()` per request. Idempotency on
  `content_sha256` exactly as the RPC: a second post of an `INGESTED` file returns
  `ALREADY_COMMITTED` and writes nothing. Quarantine writes the `ingested_files` row with the
  reason and nothing else. Rule 7 supersession (provisional rows in the window deleted) inside the
  same transaction.
- `security invoker` has no analogue; the endpoint requires a household token. The trigger from
  ticket 02 is what makes committed rows immutable, not the view.

## Corpus

`tools/gate_corpus_sql.py` and `tools/sql_check.py` drive a corpus against the SQL functions
today. This ticket ports the corpus to `server/ingest/tests/test_gate_corpus.py`: **same inputs,
asserted outcome fields identical** (`outcome`, `inserted`, `superseded`, `dupes`, `restatements`,
quarantine reason text). Until ticket 10, run both and diff; a divergence is a bug in the port,
never a corpus edit.

## Verification

- [ ] Corpus green, outcome-for-outcome against the SQL run.
- [ ] A payload with `lines: []` and every anchor zero quarantines (rule 6).
- [ ] A payload whose lines sum to the total but whose opening and closing do not reconcile
      quarantines.
- [ ] Posting the same file twice: second response `ALREADY_COMMITTED`, row count unchanged.
- [ ] `receipts.unaccounted_cents` non-null forces `UNRECONCILED`: the DB check from ticket 02
      fires, and the view's own pre-check gives the better message first.
