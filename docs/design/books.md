# Books reader (web), design notes

Books ticket 10, 2026-10-23. Studied before building; the decisions, not the pixels.

## What I looked at

| Product | Reached? | Notes |
|---|---|---|
| epub.js reader demo (`futurepress.github.io/epubjs-reader`), Moby-Dick | Yes | Screenshots `refs/epubjs-reader.png`, `refs/epubjs-reader-toc.png` |
| Thorium Web (`thorium-web.vercel.app`) | Opened, rendered a blank page in the headless browser | Nothing taken from it |
| Readium web demo at `thorium.edrlab.org` and `readium.org/thorium-web` | No, both 404 | Not described |
| Apple Books, Kindle Cloud Reader, Google Play Books | No, sign-in walls | Not described from memory |

## What I took from epub.js reader

- **Content first.** Nothing on the page but the text, a quiet top bar (contents left, title
  centred, bookmark, settings, full screen right) and edge arrows. No progress readout at all, which
  is the one thing I did not copy: LEGION's reader shows `Chapter 3 · 53%` bottom left, because the
  whole feature is "where am I, and is it where my phone was".
- **Contents is a side panel, not a page.** Opening it keeps the book visible beside it. Mine slides
  over the right edge on desktop and fills the width at 390 px.
- **Bookmarks and contents share one panel family**, so the controls live in one place. Mine are
  three panels (Contents, Bookmarks, Reading settings) opened from the same bar.
- **Chrome that gets out of the way.** The phone ruling (ticket 06) is a tap in the middle third to
  show or hide the bars; the stage keeps one fixed size either way so hiding them never re-flows text.

## Decisions for this screen

- **Library, not shelf.** A list of rows (title, format chip, "43% on your phone, 2 days ago"), not a
  cover grid: covers would mean opening and parsing every file just to draw the page, and a folder of
  books on a laptop is read by name. Rows are 56 px high and one button each.
- **First screen says what it is.** No folder chosen: one sentence and one `Choose folder` button. A
  browser without the folder picker says so in words and offers `Pick files` (one session).
- **The one upload is said in words on the page**, in the Folder panel's description, never behind a
  link: a PDF goes to the household server once so it can be refit, the server keeps nothing.
- **Sync sentences sit over the text, in words**: "Picked up where you left off on your phone." or
  "Opened near where you were on your phone." Not a colour, not an icon. The offline sentence, "Reading
  progress will sync when the server is reachable.", sits in the bottom bar opposite the place.
- **No empty chart sentence applies**; the empty states are "No bookmarks in this book yet.", "There
  are no EPUB, PDF or CBZ files in <folder>." and "This book has no table of contents."
- **Night recolours a PDF with a filter that inverts lightness and keeps hue; a comic is never
  recoloured.** The warm filter is an amber multiply layer over the page area only, so the bars and
  panels keep true colours and the switch state stays readable.
- **Keyboard**: arrows, PageUp/PageDown and space turn pages, including when focus is inside the
  book's own frame (handed up), Escape closes a panel, then the reader.

## Library choice

Readium's TypeScript toolkit (`@readium/navigator` 2.14.0, `@readium/shared` 2.8.0). The toolkit has
no EPUB reader: it wants a Readium Web Publication Manifest and a `Fetcher`, because it expects a
streamer on a server. Book files here never leave the browser, so `src/lib/books/epub.ts` is the
streamer (fflate unzip, container.xml, package document, nav or NCX). In return the locators the page
saves are Readium Locator JSON, the same shape as the phone's Readium Kotlin 3.3. epub.js was not
needed.
