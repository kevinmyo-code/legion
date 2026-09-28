---
map: backend-etl
ticket: "10"
title: "Port BofaStatementParser to Python so BofA statements skip the LLM"
type: build
status: open
blockers: ["06"]
blocked-by: ["[[06-drive-statements-watcher]]"]
tags: [ticket]
---

# Deterministic BofA on the server

CLAUDE.md §4 rule 1: deterministic first where a deterministic path exists. With ticket 09 BofA
becomes the bulk of statement volume, so it earns its parser back on the server.

- Port the Kotlin `BofaStatementParser` to `server/ingest/parsers/bofa.py` on `pdfplumber`.
  **Rule 6 binds the port:** inside a recognised section every line that is not the section total
  must parse, or the document quarantines. The interest-row shape that once slipped past the Kotlin
  check (§4 rule 6's story) gets its own test.
- ticket 06's extraction tries this parser first; unrecognised layout falls through to Gemini.
  Rows tagged `DETERMINISTIC`.
- Golden tests: the Kotlin parser's test fixtures, converted, must produce identical rows and
  anchors in Python.

## Verification

- [x] Every Kotlin fixture passes in Python with identical output.
- [x] A statement with an unrecognised line quarantines.
- [ ] Live: one real BofA checking and one real card statement dropped in the folder commit with
      no Gemini call. Owed: every fixture is synthetic, and `pypdf`'s text of a real BofA PDF is
      unmeasured (the Kotlin rules were fitted to PdfBox's text of Kevin's real statements).

## Built (2026-09-28, `feat/backend-etl`)

- **Source.** The Kotlin parsers were deleted from `app/` in `ad2d68f` (statement ingestion left
  the phone); ported from `ad2d68f^`: `BofaStatementParser.kt` (checking) and
  `BofaCardStatementParser.kt` (card), plus `LedgerMoney.kt`. The two BofA CSV parsers were not
  ported: the activity CSV is ticket 09's, and it needs an account mapping the CSV never prints.
- **Files.** `server/ingest/parsers/money.py` (exact cents), `pdf_text.py` (the thin extraction
  layer), `bofa.py` (both layouts as text parsers, plus `BofaPdfParser` objects registered as
  `bofa-checking-pdf` and `bofa-card-pdf`, kinds `{pdf}`, from `IngestConfig.ready`). The
  registry in `ingest/statements.py` is unchanged.
- **Extractor: `pypdf==6.19.0`, not pdfplumber.** Pure Python, no dependencies
  (`py3-none-any`), so the slim image needs no compiler; pdfplumber pulls Pillow and a native
  PDFium. And pdfplumber collapses runs of spaces that PdfBox kept and the Kotlin card test pins
  (`NORTHWIND OUTFITTERS      Northwind.com/billWA`); pypdf keeps them. On every other BofA
  fixture the two produce the same lines.
- **Golden tests.** All 13 BofA PDF fixtures copied to `server/tests/bofa_fixtures/`, each with
  its extracted `.txt`. The parser tests read the `.txt`; a test pins that each PDF still
  extracts to exactly that text. All 18 Kotlin test cases ported with the same rows and anchors
  (`tests/test_bofa_parser.py`).
- **Anchors per layout.** Neither prints one total for the statement, so `stated_total_cents`
  is NULL for both (rule 8) and the gate's DETERMINISTIC two-anchor branch applies.
  Checking: opening = "Beginning balance on", closing = "Ending balance on". Card: opening =
  "Previous Balance", closing = "New Balance Total", both **negated**. Section totals and the
  card's summary identity and cross-check are checked inside the parser, not persisted (no
  column exists).
- Decided in the build:
  - **Card balances are negated** (holder's side, what the Gemini prompt already says for a
    card). Kotlin returned them as printed because nothing compared them to the flipped rows;
    the server gate does, and as printed every card statement would quarantine.
  - **Rule 6 is stricter than the Kotlin checking parser.** It silently dropped a non-date line
    no open row could claim; here that quarantines. Tested with a stray line after a row and
    before the first row. The card parser already refused.
  - **A quarantine reason never quotes the statement** (the job prints results to its log);
    it names the section and row number and carries amounts, as the gate's own reasons do.
  - Nicknames `BofA checking` and `BofA card`; only the last four of the account number leave
    the parser. `line_ref` keeps the Kotlin form `<file>:'<first 60 chars of the row>'`.
  - The card's period line becomes `period_start`/`period_end`; checking sends none (the Kotlin
    never read one), so the gate falls back to the row dates.
  - Unreadable bytes, and a layout the parser does not recognise, return "not mine" and fall to
    Gemini, as `UnrecognizedLayoutException` did.
- **Tests.** `tests/test_bofa_parser.py` (63), and in `tests/test_drive_statements.py`: a BofA
  checking and a card PDF through `drive_statements` with Drive faked commit DETERMINISTIC with
  both balances persisted and stated total NULL, and Gemini never called; a refused BofA PDF
  quarantines with no Gemini call; a non-BofA PDF still reaches Gemini past the real parsers.
