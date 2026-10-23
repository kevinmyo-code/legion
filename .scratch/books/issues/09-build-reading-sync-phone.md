---
map: books
ticket: "09"
title: "Build: the phone syncs reading position and bookmarks with the engine"
type: build
status: built
blockers: ["07", "08"]
blocked-by: ["[[07-read-on-the-laptop-decision]]", "[[08-build-reading-sync-engine]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Build: phone reading sync

Rulings in ticket 07; the API is ticket 08's contract. Buildable against a fake before 08 deploys.

- **Every book gets a SHA-256**, not only PDFs. Reuse ticket 05's cache (`PrefsConversionState`
  pattern: documentId -> sha, invalidated on size or modified time). Hash off the main thread, smallest
  first, once per file.
- **Variant:** a PDF read as its EPUB copy (`<docId>#resizable-epub`) syncs as `resizable` under the
  PDF's hash; everything else is `original`.
- **Push:** every saved position and bookmark change is queued (Room, additive migration, all section 5
  rules) and sent on the existing engine path; offline it waits and the Books sheet says "Reading
  progress will sync when the server is reachable."
- **Pull:** on Books sheet open and on reader open, `GET` since the last cursor. A newer remote
  position than the local one is applied: the reader opens there and says "Picked up where you left off
  on the web" (or the device name). Applying a Readium locator that fails falls back to
  `totalProgression` and says "near".
- **Bookmarks** merge by UUID; a tombstone deletes locally.
- Through an interface `ReadingSyncBackend` with a Django impl and a test fake.

Tests: queue and retry, newest-wins on pull, tombstones, variant mapping, the "near" fallback, the
offline sentence.

Owed on the A25: read on the phone, open on the laptop at the same spot, and back.

## Built 2026-10-23 (schema 77 to 78, suite 5202/0, detekt only the 4 inherited)

Done: SHA-256 for every book through ticket 05's cache (`BookHashPass`, smallest first); variant mapping
(`<docId>#resizable-epub` is `resizable` under the PDF's hash); outbox in Room (`book_sync_outbox`, keyed so a
hundred page turns offline are one row), `book_bookmarks.syncUuid`; push, pull and tombstones in
`ReadingSyncRunner` behind `ReadingSyncBackend` (Django impl and a test fake); reader pulls with a 4 s cap
before fixing its start, says "Picked up where you left off on the web" or "near", and falls back to
`totalProgression` when the locator's chapter file is not in the book; Books sheet says "Reading progress will
sync when the server is reachable." only while writes wait on an unreachable engine.

Deviations and calls, changeable:
- **Paged locator shape (not pinned by 07/08):** this phone sends `{"page": n}` (0-based source page) for PDF
  and CBZ. Proposed amendment to ticket 08; ticket 10 must read the same. No page JSON means the reader
  falls back to `total_progression`.
- **Response envelope of the two GETs is not pinned:** the phone reads a bare array or `{"results", "next"}`.
- **Device string** is `phone`; the web shows it as "your other phone".
- **Notes:** the phone cannot type one, so it sends `note: null` on create and never re-PUTs a bookmark.
- Local bookmark delete stays a hard delete; the engine tombstone is sent from the outbox.
- A 5xx is treated as retry-later (not queued-behind-Unreachable in EngineHttp's sense, but the same effect).

Owed: **08 deployed** (needs Kevin's OK), then on the A25 plus the laptop: read on the phone, open on the
laptop at the same spot, and back; a PDF's resizable copy both ways; airplane-mode page turns then reconnect;
a bookmark added on one and deleted on the other. Also owed on hardware: the hash pass over the real folder
(35 MB books) and the 4 s pull cap with the engine off.
