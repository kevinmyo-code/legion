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
EPUB, PDF and comic files (CBZ, CBR) under one folder the user grants through the system picker, and
opens them in LEGION's own reader. The six aspects are unchanged.

**Everything stays on the phone.** Reading position, bookmarks and reader settings are local; nothing
reaches the engine. Covers come from the files themselves, so Books fetches nothing at runtime.

**Read-aloud uses the phone's own text-to-speech**, offline. The voice tool and the Listen button
call one controller (ADR 0035).

## Context

Kevin wants the A25 to be a reading device as well as an emulator handheld, one tap from HOME. He
picked prototype A (`.scratch/books/research/Main.dc.html`): its own chip and sheet, not a tab inside
Games and not a shared sheet.

## Consequences

- HOME's chip row becomes two rows of three.
- No folder granted, permission lost, an empty folder, or a format that cannot be read aloud is said
  in words with the fix.
- PdfBox stays banned (CLAUDE.md section 3); the PDF path uses the platform renderer or Pdfium.
