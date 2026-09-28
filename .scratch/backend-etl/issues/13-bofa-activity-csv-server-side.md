---
map: backend-etl
ticket: "13"
title: "BofA activity CSVs on the server: deterministic readers, the rule 7 writer, watcher routing"
type: build
status: built
status-detail: "Built 2026-09-28 on feat/bofa-activity (not merged). Server suite green by JUnit. Owed: one real card and one real checking activity CSV through the live watcher."
blockers: ["06"]
blocked-by: ["[[06-drive-statements-watcher]]"]
tags: [ticket]
---

# BofA activity CSVs, server side

Split out of [[09-bofa-statement-pull]] on 2026-09-28: the half that runs on the server. The laptop
half (the login script that PRODUCES these files) stays open in 09.

## Built (2026-09-28, `feat/bofa-activity`)

- **`server/ingest/parsers/bofa_activity.py`**, a port of the phone's deleted
  `BofaCardCsvStatementParser.kt` and `BofaCsvStatementParser.kt` (`ad2d68f^`). Layout detection is
  theirs: an exact first line. 0 Gemini tokens, no LLM anywhere.
  - **Card** (`Posted Date,Reference Number,Payee,Address,Amount`): prints no anchor, so every row is
    provisional (section 4 rule 7).
  - **Checking** (`Description,,Summary Amt.`): prints a beginning and an ending balance, so it is
    GATED (ruling 4 below): running balance per row, printed credit and debit totals, beginning +
    net = ending, then `commit_statement` as `DETERMINISTIC` with both balances in their own columns
    and `stated_total_cents` NULL (rule 8: it prints no single total).
  - Rule 6: every line accounted for or the file is quarantined with a sentence naming the row by
    position (never quoting it). Two places are stricter than the Kotlin, which skipped a line
    between the summary and the table and stopped reading at a blank line inside the table.
  - **Account = file name.** Neither export prints an account number. Accepted:
    `currentTransaction_<last4>.csv` (BofA's own) and `bofa_<last4>_activity_<YYYY-MM-DD>.csv`
    (09's script), with Drive's ` (1)` suffix. Anything else quarantines, never guesses.
  - **Signs as printed, never negated.** Ticket 12 of ledger-drive-ingestion read the real card
    file: 38 negative debits, 2 positive credits. The copied card fixture
    (`currentTransaction_7823.csv`) is synthetic and prints the opposite; it is used for layout only.
- **`server/ingest/provisional.py`**, the rule 7 writer, registered with
  `register_provisional_writer`. Rows `UNRECONCILED`, `statement_id` NULL, no header. Refuses any
  other provenance (no side door around the gate).
  - **Re-pull replaces, never duplicates.** Within the new file's window, stored provisional rows
    for the account and the file's lines are matched as multisets on `(date, amount, description)`:
    the k-th identical line keeps the k-th identical row. Unlisted stored rows are deleted
    (`forbid_mutation_of_facts` allows DELETE on UNRECONCILED only), unmatched lines inserted.
    Voice-logged pending charges (`pending_logged_at` set) are left alone.
  - **Transience both ways.** The gated side already existed and is reused:
    `commit_statement` deletes provisional rows in its window
    (`test_a_gated_statement_supersedes_provisional_rows_in_its_window`). New: a provisional line
    dated inside a window a verified statement has already listed completely (dedup's
    `_enumerated_windows`) is never written.
- **Watcher routing** (`drive_statements`): unchanged seam from 06. PDF to the parsers then Gemini
  then the gate; a recognised card CSV to the writer; a recognised checking CSV to the gate; any
  other CSV quarantined with `CSV_NOT_RECOGNISED`, never sent to Gemini.
- **"Unverified" in words.** The writer's answer carries `verification: "unverified"` and a
  sentence; the job log's line for the file says "unverified: N new, K already held, R no longer
  listed and removed, C already on a verified statement"; `GET /api/ledger/transactions/` gains
  `verification_note`, a sentence beginning "Unverified" on every UNRECONCILED row, null otherwise
  (`openapi.yaml` regenerated).
- `PROVISIONAL_REFUSAL` in `ingest/views.py` no longer points at django-engine 13.
- Tests: `server/tests/test_bofa_activity_parser.py` (no DB),
  `server/tests/test_bofa_activity_ingest.py` (watcher end to end, writer, API). Fixtures copied
  to `server/tests/bofa_fixtures/`.

## Ruling 4 (from 09): confirmed from the record, re-check on the first pull

09 ruling 4 said "reasoned from memory, NOT confirmed". It was in fact read off Kevin's real
checking export on 2026-08-03, in commit 2d188e7: "Verified against Kevin's real export before
writing a line: all three anchors hold to the cent." So the checking CSV is gated here. Box 5 in
09 is ticked only when the first real daily pull commits.

## Follow-ups, not built here

- **The web app renders no ledger rows yet** (`server/frontend/src` has no ledger screen). When one
  is built it must show `verification_note` in words for every UNRECONCILED row and label any total
  containing one as unverified (section 4 rule 7). Nothing to fix today; the rule is waiting for it.
- **The phone's ledger** must do the same with `verification_note`; the Android side is not on
  this branch.
- **`server/frontend/src/api/schema.d.ts` is stale against dev's `openapi.yaml`** by about 225
  lines unrelated to this ticket; `npm run check:api` would fail before and after this change.
  Regenerate in its own commit.
- **A checking account with no activity since the statement** produces an export with only the
  beginning-balance row. The gate quarantines it as an empty extraction (rule 6), every day it is
  pulled. Honest but noisy; 09's script could skip uploading such a file.
- **The gated checking CSV's account comes from the file name**, not the document. The anchors
  verify the money, not the identity; the script in 09 must name the file right.

## Verification

- [x] pytest: card fixture lands `UNRECONCILED`, no header, 0 Gemini calls.
- [x] pytest: same-day re-pull keeps 2, adds 1, duplicates nothing; identical bytes skipped;
      same-day duplicate coffees matched by position; a dropped row removed.
- [x] pytest: gated statement deletes provisional rows in its window; provisional lines inside a
      verified window are never written.
- [x] pytest: checking fixture commits with opening -631, closing 222061, stated total NULL; the
      balance-mismatch fixture quarantines with the figures.
- [x] pytest: unrecognised CSV quarantined `CSV_NOT_RECOGNISED`; API `verification_note`.
- [ ] Live: one real card activity CSV and one real checking activity CSV through the deployed
      watcher (needs 09's script or a manual drop, and a deploy of this branch).
