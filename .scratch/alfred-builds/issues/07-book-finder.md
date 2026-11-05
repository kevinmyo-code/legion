---
map: alfred-builds
ticket: "07"
title: "book-finder skill"
type: build
status: open
status-detail: "charted 2026-11-05"
blockers: ["06"]
blocked-by: ["[[06-book-library]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# book-finder skill

## What
- A worker skill (private folder):
  1. **Recommend 3-5 titles** for the asked mood or genre, using Kevin's reading history (books positions) when available. Each gets one line on why.
  2. Kevin picks one.
  3. **Search legal free sources only**, in order:
     - Standard Ebooks
     - Project Gutenberg
     - Wikisource
     - public-domain items on the Internet Archive
     - the author's or publisher's own free copy
  4. **Never a pirate site**, shadow library or "free download" aggregator.
  5. **Prefer EPUB.** A legal PDF goes through `/api/books/convert`.
  6. **Upload to the library** (ticket 06) with `licence_note` and `source_url`.
  7. **If no legal free copy exists**, say so and name where to borrow (Libby) or buy it. Purchases stay Kevin's.
- The summary is spoken: title, author, "it's in your Books now".
## Verification
- A dry run with a public-domain title (e.g. Dracula).
