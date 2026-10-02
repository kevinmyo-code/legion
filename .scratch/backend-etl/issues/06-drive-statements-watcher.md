---
map: backend-etl
ticket: "06"
title: "drive_statements: raw bank statements from a Drive folder, through the gate"
type: build
status: built
status-detail: "Built 2026-09-28 (67b349b), server suite 998 / 0 failures / 43 skipped (JUnit). Deployed; folder 19tqQKzPKZVm0zCVG-lt7zaERqstIPkNd set on live; first run on the empty folder ok. Owed: one real statement committing with anchors persisted."
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

- [x] pytest: the same file twice gives one statement; a mismatched total quarantines, writes no rows.
- [ ] Live: one real statement dropped in the folder commits with its anchors persisted.

## Built (2026-09-28, `feat/backend-etl`)

- `ingest/statements.py` (the job), command `drive_statements`, command
  `set_statements_folder <id or URL> [--household <uuid>]`, `ingest/folder_ids.py` (the one
  id-or-URL rule, loaded by path from `tools/connect_session.py drive --statements-folder`
  too), `DriveClient.download_bytes`, `modifiedTime` in `list_files`. Tests:
  `tests/test_drive_statements.py` (Drive faked at HTTP level, Gemini at function level).
- **The folder** is `statements_folder_id` in the `drive` credential's `config`, merged, so the
  login, scopes and backup switch are untouched. A household with a Drive login and no folder
  records `skipped` ("The statements folder is not set up."). Kevin's folder:
  `19tqQKzPKZVm0zCVG-lt7zaERqstIPkNd`.
- **The gate is one function.** `ingest.views.commit_statement(payload, household)` is the body
  `POST /api/ingest/statement` always ran, lifted out of the view, returning a `CommitResult`
  (body + status) the view turns into its `Response`. The job calls it in-process with the
  extracted payload plus the file facts. Test: a spy on it sees both the job and the view.
- **Routing, in order.** Not a PDF or a CSV (a Google-native doc, an image): skipped with a
  note, never downloaded. Listed over 20 MB: skipped with a note. Content hash already
  `INGESTED` or `QUARANTINED` in `ingested_files`: nothing more, never re-sent to Gemini (the
  watermark only narrows the listing; a test lists every file every run to prove it). Then every
  registered parser whose `kinds` include the file's kind (`register_parser`, ticket 09 and 10's
  seam). A CSV no parser recognises is quarantined: "CSV not recognised: no deterministic reader
  for this layout, and a CSV is never sent to Gemini". A PDF no parser recognises goes to Gemini.
- **Gemini:** `gemini-3.5-flash-lite` (the phone's `SubAgent.DEFAULT_MODEL`), v1beta
  `generateContent`, key in the `x-goog-api-key` header (never the URL), the PDF as
  `inlineData`, `responseMimeType: application/json` with a `responseSchema` whose fields are
  LEGION's statement format (`docs/ledger-csv-import-format.md`) as the endpoint's JSON takes
  it, `temperature 0`, 180 s timeout. A PDF over 14 MiB cannot go inline (Gemini's 20 MB request
  cap after base64) and is quarantined saying so. 401/403 fails the run ("refused
  LEGION_GEMINI_KEY"); timeout, 429 or 5xx fails the run and leaves the file for the next one
  (not recorded, watermark held below it); a 400 or an unreadable answer quarantines the file.
- **Three anchors.** A reading missing the printed total, the opening or the closing balance is
  quarantined naming which, before the gate. Then the gate: `sum(lines) == stated total` and
  `closing - opening == sum(lines)`, any mismatch quarantines, nothing partial written.
  `LLM_RECONCILED`; a parser's rows `DETERMINISTIC`. Integer cents only: a float from the model
  quarantines ("could not be checked"), never rounded. Anchors persist on `public.statements`.
- **Quarantine in words.** Recorded on `ingested_files` with `source_file_id = drive:<id>` and
  the reason. `/api/freshness` for `drive_statements` appends, for 30 days: "N statement file(s)
  quarantined and nothing from it was written: <names>. Reason: <newest reason>".
- **No key:** PDFs are held (not recorded, re-listed next run), CSVs and parsers still run, and
  the run records `skipped` with "LEGION_GEMINI_KEY is not set on the server, ..." which
  freshness says as "The statements folder is not being read. ...".
- **Drive:** the vault's `drive` token; `invalid_grant` or a 401 is `needs_login` (the
  credential is stamped refused). Only files directly in the folder (`'<id>' in parents`).
- `line_ref` (NOT NULL on `ledger_transactions`) is `<file name>:<n>`, 1-based in printed order,
  unless a parser sets its own.
- `LEGION_GEMINI_KEY` joins `SECRET_ENV_VARS` (`deploy/cloudrun/_common.py`) and
  `deploy/.env.example`. `deploy/crontab`: `0 */6 * * * python manage.py drive_statements`.
- Decided in the build, not by this ticket:
  - **The model fills the format's own `stated_total_cents`**, the PRINTED total or net
    movement, null when the statement prints no single figure. The WIP had the model list every
    printed activity total and computed the net here; dropped for "exactly the format", which
    means a statement printing only separate in/out totals (BofA's card statement, reasoned)
    quarantines for want of a printed total until ticket 10's parser reads it.
  - **A parser may return a provisional result** (`Parsed(provisional=True)`): provenance
    `UNRECONCILED`, sent to the writer ticket 09 registers (`register_provisional_writer`),
    never to the gate. With no writer registered that fails the run loudly.
  - **A parser that refuses a document** (`ParserRefused`) quarantines it, Gemini not asked.
  - A Drive error on one download (not a refusal) leaves that file for the next run.
- Owed: box 2, live. After deploy and `migrate`: set the folder, then
  `gcloud run jobs execute legion-worker --region us-south1 --args=manage.py,drive_statements`
  with one real statement in the folder, and read `statements` for its three anchors.
