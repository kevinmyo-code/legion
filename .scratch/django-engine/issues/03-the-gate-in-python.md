---
map: django-engine
ticket: "03"
title: "The section 4 gate in Python: same payload, same verdicts, same corpus"
type: build
status: built
status-detail: "Built 2026-09-07. server/ingest/: gate.py (pure, no ORM - check_statement/check_receipt, every branch quoting the SQL line it mirrors), dedup.py (the port of private.ledger_resolve_dedup, two passes over a shared depleting credit pool), views.py (POST /api/ingest/statement and /api/ingest/receipt, one transaction.atomic() per request, idempotency on content_sha256, quarantine writes the ingested_files row and nothing else). Corpus: app/src/test/resources/gate-corpus.json now has a THIRD reader (tests/gate_corpus.py + tests/test_gate_corpus.py), not a fourth fixture set - all 17 cases run twice, once against the pure functions and once end to end over HTTP, and the payload mapping is copied from tools/gate_corpus_sql.py so both sides send the same request. 325 tests, 0 failures, 0 errors (JUnit XML; baseline was 254, so 71 new: 35 corpus, 21 API, 8 dedup, 7 money). ruff clean, manage.py check clean, makemigrations --check clean - no migration was generated and none was needed. SCOPE, decided by the coordinator before any code: the request body is the RPC's payload unchanged and EXTRACTION STAYS ON THE PHONE (see the section at the foot of this file). RULE 8 AUDIT, the ticket's own owed item: both server paths are already clean, verified against the live schema by read-only information_schema/pg_constraint query - statements has stated_total_cents (nullable, guarded by statements_total_only_null_if_deterministic), opening_balance_cents and closing_balance_cents; receipts has total_cents, subtotal_cents, tax_cents, other_charges_cents and unaccounted_cents. Nothing missing, so no migration against public was written. RULE 7 IS OUT OF SCOPE AND THAT IS A FINDING, not an omission: neither commit path can write an UNRECONCILED row and neither ever could (statements_not_provisional, ledger_txn_header_matches_provenance, and receipts_not_provisional as narrowed 2026-08-26, which commit_receipt cannot satisfy because it never writes unaccounted_cents), so the verification item asking for a provisional store was replaced by one asserting the quarantine SENTENCE - see ticket 13. Blocker 02 cleared: 02 is status built, its models are what this ticket writes through, and all 41 of its read-only round-trip tests pass against the live schema. What 02 still owes (its own Method/Verification sections describe a managed=True approach its build did not take) does not touch this ticket. TEST PATH DIVERGENCE: this file says server/ingest/tests/test_gate_corpus.py; the tests are in server/tests/ instead, because pyproject.toml sets testpaths = [\"tests\"] and all 254 existing tests live there. OWED: a run against the deployed engine rather than a pytest database, and the parallel-run diff against the SQL side (tools/gate_corpus_sql.py against the live project) that ticket 10 turns off."
blockers: []
blocked-by: []
open-blockers: 0
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
today. This ticket ports the corpus to `server/tests/test_gate_corpus.py`: **same inputs,
asserted outcome fields identical** (`outcome`, `inserted`, `superseded`, `dupes`, `restatements`,
quarantine reason text). Until ticket 10, run both and diff; a divergence is a bug in the port,
never a corpus edit.

Two corrections to the paragraph above, made when it was built rather than left to rot:

1. **The path said `server/ingest/tests/test_gate_corpus.py`.** The tests are in `server/tests/`,
   because `pyproject.toml` sets `testpaths = ["tests"]` and all 254 pre-existing tests live
   there. A second test root would have needed a config change to be collected at all.
2. **The corpus is not COPIED, it is read where it lies** - `app/src/test/resources/gate-corpus.json`,
   the same file `GateCorpusTest.kt` and `tools/gate_corpus_sql.py` read. Two copies of a file
   whose entire job is to be one shared source would defeat the mechanism. `server/tests/gate_corpus.py`
   is the loader, and it raises rather than skipping when the file is missing: a corpus that
   quietly loaded nothing would be rule 6's own failure shape pointed at the test suite.

`outcome` is asserted per case, matching what the SQL script asserts. `inserted`, the dedup
counters and the quarantine wording are pinned in `test_ingest_api.py` and `test_dedup.py`, where
the surrounding state is controlled rather than accumulating across seventeen cases.

## Verification

- [x] Corpus green, outcome-for-outcome. All 17 cases, twice each (pure and over HTTP).
      **Owed: the diff against a live SQL run.** `tools/gate_corpus_sql.py` still has to be
      pasted into the project's SQL editor and the two outcome lists compared, which is the
      parallel-run this ticket promises and ticket 10 ends. Not done here: this session had no
      write access to the live database, by instruction.
- [x] A payload with `lines: []` and every anchor zero quarantines (rule 6).
      `test_an_empty_extraction_quarantines_even_when_every_figure_is_zero`.
- [x] A payload whose lines sum to the total but whose opening and closing do not reconcile
      quarantines. `test_a_statement_whose_balances_disagree_quarantines_and_writes_nothing`.
- [x] Posting the same file twice: second response `ALREADY_COMMITTED`, row count unchanged.
      Both aspects.
- [ ] ~~`receipts.unaccounted_cents` non-null forces `UNRECONCILED`~~ - **struck, and the reason
      is ticket 13.** This step assumed the commit path can write a provisional receipt. It
      cannot: `commit_receipt` never writes `unaccounted_cents` at all, and
      `receipts_not_provisional` permits `UNRECONCILED` only when that column is non-null, so
      the two conditions are mutually unreachable through this endpoint. The check is real and
      is mirrored into the test schema; what is missing is a path that could ever trip it.
      Replaced by `test_a_provisional_payload_is_refused_in_words_and_writes_nothing`.

## Extraction is not here, and what it waits on

Decided by the coordinator, 2026-09-07, when the dispatch brief asked for
`POST /api/pantry/receipts` as multipart-with-the-photo and Django running Gemini itself. That is
the eventual shape and it is **not this ticket**:

- it depends on [[05-media-photos-and-audio]], which is open and was repointed at Cloudflare R2;
- it depends on a ruling nobody has made about **where a user-owned server-side LLM key lives**.
  CLAUDE.md is BYO throughout and "no Kevin-hosted anything"; a key baked into the image is the
  thing that rule forbids, and a per-household key needs a home, a rotation story and a place in
  the admin. That is a decision, not an implementation detail.

So the caller still extracts and this endpoint still gates. When extraction does move, the gate
does not change: it already takes lines and anchors and cares nothing about where they came from.

## Rule 7 has no endpoint, and that is its own ticket

[[13-provisional-ingestion-has-no-endpoint]]. Neither commit path can store an `UNRECONCILED` row
and neither ever could - the constraints forbid it three different ways. The honest behaviour here
for a document that states no anchor is a quarantine that says so in words, which is what the
no-stated-total branch does.
