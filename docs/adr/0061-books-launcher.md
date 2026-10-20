---
status: accepted
decided: 2026-10-19
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-10-19 - Books on HOME: a launcher section with LEGION's own reader (Kevin)]]"
tags: [adr]
---

# 61. Books are a HOME launcher with LEGION's own reader, read from a folder grant

## Standing

**Books is a launcher on HOME, beside Games, not an aspect.** A Books chip opens a sheet listing the
EPUB, PDF and comic files (CBZ read; CBR listed but not readable, Kevin 2026-10-19) under one folder the user grants through the system picker, and
opens them in LEGION's own reader. The six aspects are unchanged.

**Everything stays on the phone.** Reading position, bookmarks and reader settings are local; nothing
reaches the engine. Covers come from the files themselves, so Books fetches nothing at runtime.

**Read-aloud uses the phone's own text-to-speech**, offline. The voice tool and the Listen button
call one controller (ADR 0035).

**The engine is a hybrid (Kevin, 2026-10-19).** Readium 3.3.0 reads EPUBs and reads them aloud, and
nothing else. PDF is LEGION's own viewer on Android's `PdfRenderer`. Comics are LEGION's own viewer,
a CBZ read as a zip of images in natural order. CBR is listed but not readable, and opening one says
"CBR is not supported. Convert it to CBZ to read it here." Read-aloud is for ebooks; PDF and comics
say so in words. Readium's `media3-exoplayer` is excluded from the build, so Media3 stays as it was
in CLAUDE.md section 3 (the session library only). Position and bookmarks are two local-only Room
tables (`books`, `book_bookmarks`); reader settings are SharedPreferences.

## Context

Kevin wants the A25 to be a reading device as well as an emulator handheld, one tap from HOME. He
picked prototype A (`.scratch/books/research/Main.dc.html`): its own chip and sheet, not a tab inside
Games and not a shared sheet.

## Consequences

- HOME's chip row becomes one row of six slim chips with no subtitle line (Kevin, 2026-10-19; the
  prototype's two rows of three cost HOME an agenda row). Counts ride in each chip's TalkBack description.
- No folder granted, permission lost, an empty folder, or a format that cannot be read aloud is said
  in words with the fix.
- PdfBox stays banned (CLAUDE.md section 3); the PDF path uses the platform renderer or Pdfium.
