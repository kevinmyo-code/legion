---
map: books
ticket: "03"
title: "Decision: PDF text that refits - convert on the server, pass-through, every PDF"
type: decision
status: resolved
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Decision: PDF text that refits

Kevin, 2026-10-23: *"for books, sometimes the font is too small. i wanna make it bigger but its just a
zoom on the pdf screen. how can we make the fonts refit instead? do we convert the pdf to something
else ourselves?"*

A PDF is fixed layout: `PdfRenderer` draws a picture of a page, so bigger means zoom. EPUB already
refits (Readium, the reader's A+ / A-). Options put to Kevin: get the EPUB edition, convert on the
server, a reflow mode on the phone (needs a PDF text library; PdfBox is dropped by ruling), crop
margins. **He picked server conversion.**

## Rulings (Kevin, 2026-10-23, all four by interview)

1. **Pass-through.** The phone sends the PDF to the household engine, the engine converts it and returns
   the EPUB in the same response, and **nothing is kept on the server**: no row, no file, no log of
   content. ADR 0061's "Everything stays on the phone" gains this one exception, amended narrowly.
2. **Every PDF, automatically.** Each PDF in the granted folder is converted in the background without
   a tap. Kevin chose this over a per-book button knowing every PDF he adds goes to his server.
3. **The EPUB lives inside LEGION**, in app-private storage, never written into the books folder. Lost
   if app data is cleared, and then simply converted again.
4. **Scans are said in words, no OCR.** A PDF with no text layer (scanned pages, sheet music) returns
   "This PDF is scanned pages with no text, so its text cannot be refit." and is never retried.

## Facts at decision time (traced 2026-10-23)

- PDFs on the A25 under `Books/`: novels of 0.4 to 2.2 MB, two sheet-music PDFs, a 12.4 MB textbook
  (Hillier-Lieberman), and `The Green Book.pdf` at 35.6 MB.
- Cloud Run service `legion` (us-south1): 1 CPU, 512 MiB, **60 s timeout**, concurrency 80. Request
  bodies over 32 MiB are rejected by Cloud Run's front end on HTTP/1.
- Docker is not installed on the charting machine, so the image change cannot be built locally.

Build tickets: 04 (server), 05 (phone). Reader comfort asked the same day is ticket 06.
