---
map: aspect-engine
ticket: "01"
title: "Can the app write files back into a Drive folder through SAF?"
type: research
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Can the app write files back into a Drive folder through SAF?

## Question

Ledger proves SAF *read* from a Google Drive folder (the Drive documents provider serves PDFs to
`LedgerImportActivity`). The xlsx mirror needs the opposite direction: the app **creates and
updates .xlsx files** inside a user-picked Drive folder, and Drive syncs them to the cloud where
the user opens them in Sheets.

Establish from primary sources (Android SAF docs, `DocumentsContract`, Google Drive Android app
documentation/release notes, credible issue trackers):

1. Does the Drive documents provider advertise `FLAG_DIR_SUPPORTS_CREATE` and
   `FLAG_SUPPORTS_WRITE` on folders/files, and on what Drive app versions?
2. Does a file written through the provider actually upload, and when (immediately, on wifi,
   on app open)? Is there a documented staleness window where a read returns the pre-edit bytes?
3. Persistable URI permissions across reboots for a Drive tree URI - same guarantees as local?
4. Known failure modes: offline writes, conflict when the cloud copy changed, file size limits.
5. If write-back is unreliable: assess the fallback - app writes the mirror to a local folder the
   user points the Drive app's folder-sync/backup at, or the files live local-only and upload is
   manual share. What does the fallback cost the audit-and-edit loop?

**Do not answer from memory. Cite docs or reproducible reports.** On-device verification on the
A25 stays owed regardless of what the docs say - name it as such in the answer.

Findings go to `.scratch/aspect-engine/research/01-drive-folder-write-back.md`.
