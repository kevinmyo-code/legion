---
type: decision
status: open
blocked_by: []
map: backend-erp
---

# Statement ingestion leaves the phone entirely

**Ruled 2026-08-28 by Kevin:** *"as for ledger, it will be ingest via the web app. phone doesnt
ingest statements. only the photo receipts for pantry."*

## The split, stated plainly

| Ingestion | Where |
|---|---|
| Bank statements | **Web app only.** The phone never ingests one again |
| Pantry receipt photos | **Phone only.** The camera is on the phone; this stays |

That is a clean line and it follows ADR 0040's own logic: the phone keeps what needs hardware a
browser cannot reach. A receipt photo needs a camera. A statement PDF is already on the laptop.

## What this makes dead on the phone

Every one of these exists ONLY to ingest statements. **Verify each before deleting - this list is
reasoned from names and needs grepping:**

- `ledger/parsers/` - `DbsStatementParser`, `BofaStatementParser`, `BofaCardStatementParser`,
  `BofaCardCsvStatementParser`, `StatementDispatcher`, `PdfWords`, `PdfText`
- **PdfBox-Android itself**, and with it the `PDFBoxResourceLoader.init` trap that crashed the dry
  run three times today, and the ~68 MB of bundled font/glyph assets it ships
- `service/IngestScanner` and the SAF folder plumbing (`LedgerFolderPreferences`), the one-grant-at-
  a-time constraint, and every folder-reconnect problem that came with it
- The `import_statement` voice tool and its file picker
- `ledger/ReingestDryRun` and its screen - already moot since ticket 19 was killed
- `LedgerStatementAgent`, the LLM fallback path

**Not dead:** `ledger_transactions` and every read surface. The phone still SHOWS money; it just
stops being where money gets in. `IngestPipeline`'s pantry half stays.

## Why this is worth doing rather than just leaving inert

Dead ingestion code on the phone is not free. It is the largest single dependency in the app
(PdfBox plus assets), it owns the most fragile permission model in the app (persisted SAF grants,
which have now been destroyed twice by an uninstall), and it is the source of the most device-time
spent for the least value across this whole map. Leaving it in place means every future session has
to reason about whether a change affects it.

## Sequencing, and it matters

**Do not delete anything until the web app actually ingests a statement successfully.** The
knowledge of how these formats parse lives in this code and in nothing else; ticket 22 asks whether
that knowledge moves to Python or is abandoned for the LLM CSV path. Deleting first would make that
question unanswerable.

**So: ticket 22 first, then the web app's ingestion works, then this deletion.** Three gates in
order, and this ticket is the last of them.

## Consequence for ticket 22

Its question was *"should deterministic parsing come back on the PC?"* - and it noted the answer
could be "yes in Python and yes-still-delete in Kotlin." **That is now the confirmed shape of the
answer for the Kotlin half**, whatever is decided for Python: the phone's parsers go either way,
because the phone no longer ingests. Ticket 22 narrows to a single question - does the PC parse
deterministically, or does it use the LLM CSV path.
