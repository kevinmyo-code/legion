---
map: books
ticket: "02"
title: "Build: Books chip, sheet, folder scan, reader (EPUB/PDF/comics), read-aloud, voice tool"
type: build
status: open
blockers: ["01"]
blocked-by: ["[[01-books-launcher]]"]
open-blockers: 0
ready: true
tags: [ticket]
---

# Build: Books

The spec is prototype A: `research/Main.dc.html` (HOME + sheet) and `research/Reader.dc.html`
(reader). Rulings in ticket 01.

## What to build

- **Chip row.** Two rows of three: To-dos, Lists, Ideas / Games, Books, More. Books chip subtitle
  "N books", violet tone as in the prototype.
- **Sheet.** Title "Books", subtitle count and folder. Continue reading card (cover, title, chapter
  or page, percent, progress bar, last read; Read and Listen buttons). Shelves: Ebooks (EPUB),
  Documents (PDF), Comics (CBZ/CBR); a shelf with no files is not shown. Tile = cover + progress bar.
  No folder granted, folder permission lost, or empty folder: said in words with the fix, never an
  empty shelf.
- **Scan.** SAF tree walk like `games/RomScan.kt`. Covers and titles from the file itself (EPUB
  metadata and cover image, PDF first page, first image of a comic archive), rendered and cached on
  the phone. No network. A cover that cannot be read shows title and format in words.
- **Reader.** One screen, three modes:
  - EPUB reflows: text size, Day / Sepia / Night, Literata or system serif, contents, bookmarks.
  - PDF: page view, pinch zoom, page turn.
  - Comics: page view, pinch zoom, page turn.
  - Bottom bar: page or location, percent, time left in chapter (EPUB), Contents, Listen.
  - Position saved on every page turn and on leaving; reopening resumes exactly.
- **Read-aloud.** Device TTS from the current position, sentence highlighted, play/pause, speed,
  keeps going with the screen off as a media session (lock-screen controls), stops on audio-focus
  loss. EPUB always; PDF where the page has a text layer; comics: not offered, said in words.
- **Voice.** A tool to continue reading / read aloud the current book and to open a named book;
  its failure result says what did NOT happen (no folder, book not found, TTS unavailable). Both
  the tool and the buttons call one `BooksController` (ADR 0035).
- Local storage: a Room table for books and positions is a migration (CLAUDE.md section 5 rules),
  or a small file store if Room is not warranted; say which and why.

## Engine choice (orchestrator's call, verify before building on it)

**Readium Kotlin toolkit** (BSD-3) is the expected base: its navigator covers EPUB reflow, PDF
(through an adapter) and image publications (CBZ), and ships a TTS navigator over Android TTS.
Verify on the actual versions: which PDF adapter (must not be PdfBox, banned in CLAUDE.md
section 3; prefer Android's PdfRenderer or Pdfium), whether CBR (RAR) needs a separate reader and
its license, APK size cost, minSdk. If Readium does not fit, stop and report before swapping it.

## Verification

compileDebugKotlin, testDebugUnitTest, detekt, Room migration test if Room changes, screenshot
tests of the sheet states and the reader's three themes. Owed on the A25: folder granted, one EPUB,
one PDF and one comic opened, resume after closing, Listen with the screen off.
