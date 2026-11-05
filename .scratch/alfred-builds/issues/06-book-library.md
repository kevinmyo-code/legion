---
map: alfred-builds
ticket: "06"
title: "Book library on the engine, synced to the phone"
type: build
status: open
status-detail: "engine half built 2026-11-05 (feat/alfred-builds-worker); phone half owed (Gradle)"
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Book library on the engine, synced to the phone

## What
- **Engine `books` app:** `Book(title, author, format epub, sha256, size, source_url, licence_note, added_by, added_at)`, with the file in a private bucket (the alfred-ota bucket pattern) or the household media store.
  - `POST /api/books/library` (member, multipart, EPUB only, size cap).
  - `GET /api/books/library` (list).
  - `GET /api/books/library/<id>/download` (signed URL).
  - `licence_note` is required: "public domain (Gutenberg)", "free from the author", and so on.
- **Phone:** new library books download on Wi-Fi into the existing books folder (`BooksFolderStore`) and show in Books. Reading positions already sync.
- **The existing PDF to EPUB converter** (`books/convert.py`, `/api/books/convert`) is reused for PDFs.
## Verification
- pytest: upload, list, licence required, tenancy.
- JVM: the phone sync puts a file in the folder.
