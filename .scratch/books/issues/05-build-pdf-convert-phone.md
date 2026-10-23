---
map: books
ticket: "05"
title: "Build: phone converts every PDF in the background and reads the EPUB copy"
type: build
status: open
blockers: ["03", "04"]
blocked-by: ["[[03-pdf-refit-decision]]", "[[04-build-pdf-convert-server]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Build: phone side of PDF refit

Rulings in ticket 03. Calls ticket 04's endpoint; buildable against a fake backend before 04 deploys.

## Identity and state

- **A PDF is identified by the SHA-256 of its bytes.** The EPUB is stored at
  `filesDir/books/converted/<sha256>.epub`. A renamed or moved PDF reuses it; an edited one converts anew.
- **Room table `book_conversions`** (additive migration, verbatim generated SQL, `exportSchema`, schema
  JSON committed, `SCHEMA_VERSION` bumped in lockstep, migration test):
  `sha256 TEXT PK, status TEXT NOT NULL` (`DONE`, `SCANNED`, `TOO_BIG`, `UNREADABLE`, `FAILED`),
  `attempts INTEGER NOT NULL`, `message TEXT` (the server's sentence), `updatedAt INTEGER NOT NULL`,
  `pages INTEGER`.
- **Never retried:** `SCANNED`, `TOO_BIG`, `UNREADABLE`. **`FAILED` retried up to 3 attempts**, then
  stops and stays said.
- An EPUB file missing while the row says `DONE` (data cleared, storage pruned) resets the row and
  converts again.

## Worker

- WorkManager unique work `books-pdf-convert`, constraints: **unmetered network**, battery not low.
  Enqueued after every folder scan. Processes PDFs one at a time, smallest first.
- PDFs over 30 MB are marked `TOO_BIG` on the phone without uploading, with the same sentence the
  server would send.
- Through an interface `BookConvertBackend` with a Django implementation and a test fake (CLAUDE.md
  section 8: an interface at a seam that needs a fake).
- Server unreachable: the work retries on WorkManager's backoff and the sheet says so in words.

## Reader and sheet

- **A PDF with a `DONE` conversion opens the EPUB copy in the Readium reader by default**, so A+ / A-
  refit the text. The row carries the word "Resizable".
- **The EPUB reader shows, in words, once per open:** "Converted from PDF. Figures and tables may be
  missing." with **Open original** one tap away (opens the PDF viewer).
- PDF viewer gains a line for the other states, in words: "Converting to resizable text...",
  "Waiting for Wi-Fi to convert", "<server's sentence>" for SCANNED / TOO_BIG / UNREADABLE, "Could not
  convert after 3 tries: <message>" for spent FAILED.
- Books sheet subtitle carries a count when any are pending: "2 PDFs waiting for Wi-Fi".
- Reading position: the EPUB copy and the PDF keep separate positions (different locators). Not
  merged; say nothing about it.
- Read-aloud: the EPUB copy is an ebook, so Listen works on it. That is a consequence, not new code.

## Tests

Worker state machine against the fake backend (each status, retry cap, missing-file reset, size gate,
smallest-first order), SHA-256 identity, migration test, the sheet/viewer sentences per state.

## Owed on the A25 after 04 deploys

The Road converts and A+ refits it; sheet music says scanned; The Green Book says too big; the
textbook converts or says why not.
