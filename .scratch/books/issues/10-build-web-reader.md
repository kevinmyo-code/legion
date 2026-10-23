---
map: books
ticket: "10"
title: "Build: a Books page in the web app that reads a laptop folder, synced with the phone"
type: build
status: built
status-detail: "Built 2026-10-23 against a mock of 08 and a hand-typed client (src/api/books.ts, replace with generated types when 08 lands). Vitest and a 7-case Playwright smoke green. Owed: 08 and 04 deployed; the web reader then pointed at the real engine; Kevin's laptop round trip (read on the phone, open here at the same spot, and back); the PDF resizable copy against the real converter; the Readium EPUB middle-third tap and in-frame keys checked by hand on real books (fixtures only so far)"
blockers: ["07", "08"]
blocked-by: ["[[07-read-on-the-laptop-decision]]", "[[08-build-reading-sync-engine]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Build: web Books reader

Rulings in ticket 07; API is ticket 08's contract. `server/frontend/` (React 19, Vite, TanStack
Router). Buildable against a mock of 08 before it deploys.

## Folder

- **File System Access API** (`showDirectoryPicker`), the handle kept in IndexedDB so the folder is
  remembered. Chrome and Edge have it; a browser without it says so in words and offers picking files
  one at a time instead.
- Walk the folder for `.epub`, `.pdf`, `.cbz` like the phone's scan. Hash each file with
  `crypto.subtle.digest('SHA-256')`, cached by name+size+lastModified in IndexedDB.
- **Book files are read in the browser and never uploaded**, except a PDF sent to
  `POST /api/books/convert` (ticket 04) to read its resizable copy. The converted EPUB is cached in
  IndexedDB (or OPFS) by hash, never re-uploaded.

## Reader

- **EPUB:** Readium's TypeScript toolkit (`@readium/navigator` / `@readium/shared`) if it runs in this
  stack, so locators match the phone; else epub.js with a locator adapter. Say which in the commit.
- **PDF:** pdf.js. A PDF with a resizable copy opens the copy by default, with the same "Converted from
  PDF. Figures and tables may be missing." line and "Open original".
- **CBZ:** images in natural order, like the phone.
- Day / Sepia / Night (PDF recoloured, comics never), warm filter with strength, text size for EPUB,
  full screen (browser Fullscreen API). Keyboard: arrows turn pages.

## Sync

On open, `GET` positions and bookmarks; jump to the newer of local and remote, saying "Picked up where
you left off on your phone" or "near" when only `totalProgression` could be applied. Save position on
page turn (debounced) and on tab hide. Offline: queue in IndexedDB and say so.

## Design

Use the `frontend` agent's process (study a shipped reader, then build). Matches the web app's current
look (ADR 0053, soft Material light). Desktop-first, works at phone width.

## Tests

Vitest for hashing cache, locator fallback, newest-wins, offline queue. Playwright smoke: pick a fixture
folder (mocked handle), open an EPUB, turn a page, position PUT fires.

Owed: Kevin opens a book on the laptop that he read on the phone and lands on the same page.

## Built (2026-10-23)

- Reader library: **Readium TS toolkit** (`@readium/navigator` 2.14.0, `@readium/shared` 2.8.0) plus pdf.js
  6.4.299 and fflate 0.8.3. Readium ships no EPUB parser, so `src/lib/books/epub.ts` is the streamer.
- Design notes and screenshots: `docs/design/books.md`, `docs/design/refs/books-*.png`.
- Locator shapes (fixed by the phone): EPUB = Readium Locator JSON; PDF and CBZ = `{"page": n}` 0-based.
  `device` sent is `web`. List GETs accept a bare array or `{results, next}`.
- `/books` is a desk rail item only (family tabs stay four).
- Not built: warm filter Auto (sunset); ticket 10 asks for strength only.
- Paths follow ticket 08 exactly, no trailing slash; the games routes have one. Confirm when 08 lands.
