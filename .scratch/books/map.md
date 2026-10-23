---
map: books
title: "Books, one tap from HOME"
charted: 2026-10-19
charted-by: "Kevin + Opus"
effort: "`.scratch/books/`"
tickets: 2
open: 1
status: open
tags: [map]
---

# Books, one tap from HOME

**Kevin, 2026-10-19:** *"i want my own ebook reader embedded into the launcher as well. so right now
i have games right, i also want books there as a section."* Picked prototype **A**
(`research/Main.dc.html`, reader in `research/Reader.dc.html`). ADR 0061.

## Destination

**Shipped, and a book read on the A25.** A Books chip on HOME beside Games opens a sheet: Continue
reading (cover, chapter, percent, Read and Listen), then shelves for Ebooks (EPUB), Documents (PDF)
and Comics (CBZ; a CBR is listed but says it is not supported). A tap opens LEGION's own reader. Listen reads aloud with the phone's voice.

## Tickets

- 01 decision (resolved): a launcher section like Games, folder grant, phone-only progress, device TTS
- 02 build: Books chip, sheet, scan, reader for EPUB/PDF/comics, read-aloud, voice tool
- 03 decision (resolved 2026-10-23): PDFs converted to EPUB on the engine, pass-through, every PDF
- 04 build: server `POST /api/books/convert`
- 05 build: phone worker, EPUB copy in the reader, states said in words
- 06 build: full screen, Day/Night toggle reaching PDFs, warm filter
- 07 decision (resolved 2026-10-23): read on the laptop in the web app, position + bookmarks synced
- 08 build: engine tables and API for reading sync
- 09 build: phone syncs position and bookmarks
- 10 build: web Books reader on a laptop folder
