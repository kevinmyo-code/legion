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

- [ ] Every Kotlin fixture passes in Python with identical output.
- [ ] A statement with an unrecognised line quarantines.
