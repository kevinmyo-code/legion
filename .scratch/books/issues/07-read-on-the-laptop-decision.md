---
map: books
ticket: "07"
title: "Decision: read on the laptop in the web app, position and bookmarks synced through the engine"
type: decision
status: resolved
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Decision: read on the laptop, progress synced

Kevin, 2026-10-23: *"i want to be able to read my books on this laptop, with progress synced to the
legion as well"*

## Rulings (Kevin, 2026-10-23, by interview)

1. **The laptop reader is a Books page in LEGION's web app**, not a desktop program. Same comforts as
   the phone reader (Day / Sepia / Night, warm filter, text size, full screen).
2. **Book files come from a folder on the laptop.** The web app is pointed at a local folder holding
   the same files (copied, or synced by Drive). **Book files never reach the engine** except a PDF's
   pass-through conversion (ticket 03).
3. **A book is matched across devices by the SHA-256 of its file bytes.** Same file on both, same book.
   A different edition or a re-saved file is a different book, said in words where it matters.
4. **Position and bookmarks sync**; reader settings stay per device. Positions: **newest wins**, by the
   time the reader recorded it.
5. **A PDF read as its resizable EPUB copy is read as that copy on the laptop too.** The laptop asks
   the engine to convert the same PDF (pass-through, ticket 04) so the position lines up.

## Orchestrator calls, changeable

- **Sync is per person, not per household.** Mia's place in a book is hers. Rows are scoped by
  household at the tenancy choke point and keyed by user.
- **What is synced is a locator, not text.** A position row holds the book's hash, which variant
  (`original` or `resizable`), a locator (Readium Locator JSON for EPUB, page index for PDF/CBZ), a
  0-1 total progression, a chapter label, and the time. No title, no filename, no book text: the engine
  learns that a book with hash X is 43% read, nothing more. Bookmark rows add an optional note Kevin
  typed (first-party, so storable).
- **Locator compatibility:** the web reader uses Readium's TypeScript toolkit if it can, so EPUB
  locators are the same shape the phone's Readium Kotlin emits. Where a locator from the other device
  cannot be applied (different converter output, missing href), the reader falls back to
  `totalProgression` and says "Opened near where you were on your phone" rather than claiming the exact
  spot.

ADR 0061 amended. Build tickets 08 (engine), 09 (phone), 10 (web).
