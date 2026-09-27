---
map: backend-etl
ticket: "06"
title: "drive_statements: raw bank statements from a Drive folder, through the gate"
type: build
status: open
blockers: ["02", "03"]
blocked-by: ["[[02-session-vault-and-login-handover]]", "[[03-backup-nightly-to-drive]]"]
tags: [ticket]
---

# drive_statements

Ruling 3. Kevin drops raw bank PDFs/CSVs in one Drive folder; the server does the rest.

- Folder id lives in the `drive` credential's `config`. Listing uses `modifiedTime > watermark`.
- Idempotency: `ingested_files` on `(household, content_sha256)`. A file already committed or
  quarantined is skipped and never re-sent to Gemini.
- Extraction: deterministic first where a Python parser exists (porting `DbsStatementParser` and
  `BofaStatementParser` to `pdfplumber` is a follow-up, not a blocker); otherwise Gemini with the
  household's own key, emitting LEGION's statement CSV format.
- **Then the same code path as `POST /api/ingest/statement`** (`ingest/gate.py`), called
  in-process, never duplicated. Three anchors (printed total, opening, closing), quarantine on any
  mismatch, `LLM_RECONCILED` or `DETERMINISTIC` provenance, anchors persisted (CLAUDE.md §4 rules
  2-8). A quarantined file is recorded with its reason and surfaces through freshness.
- Masking: raw documents now reach Cloud Run and Gemini unmasked. Recorded in `decisions.md`
  2026-09-27 as a knowing change to §4's 2026-08-25 amendment; CLAUDE.md §4 rule 1 amended to match the same day.

crontab: `0 */6 * * * manage.py drive_statements`.

## Verification

- [ ] pytest: the same file twice gives one statement; a mismatched total quarantines, writes no rows.
- [ ] Live: one real statement dropped in the folder commits with its anchors persisted.
