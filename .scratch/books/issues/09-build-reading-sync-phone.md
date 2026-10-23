---
map: books
ticket: "09"
title: "Build: the phone syncs reading position and bookmarks with the engine"
type: build
status: open
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
