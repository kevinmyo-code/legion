---
map: aspect-engine
ticket: "01"
title: "Can the app write files back into a Drive folder through SAF?"
type: research
status: resolved
status-detail: "Write-back works with caveats: rwt + read-back-verify mandatory, upload timing undocumented, named fallback does not exist on Android; A25 probe owed"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
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

## Answer

**WORKS-WITH-CAVEATS.** Full findings with citations:
`.scratch/aspect-engine/research/01-drive-folder-write-back.md`.

1. **Create/write advertised: YES.** The repo's own Drive APK disassembly (ledger research,
   2026-08-01) shows root `FLAG_SUPPORTS_CREATE`, real `createDocument`/`deleteDocument`/
   `renameDocument`/`moveDocument`/`copyDocument`, write modes "w"/"wt"/"rwt", and the 2026-08-02
   device probe saw `FLAG_SUPPORTS_WRITE` on every file (`flags=455`). `FLAG_DIR_SUPPORTS_CREATE`
   on the folder row itself was never queried - reasoned, not tested.
2. **Upload happens, timing NOT ESTABLISHED.** One credible report of a SAF write appearing on a
   PC "instantly"; no official doc says when. Writes land in Drive's local store and its sync
   machinery uploads on its own schedule; the download direction measured a 2m36s+ staleness
   window, assume the same both ways. Read-back through the provider can return pre-sync bytes.
3. **Persistable tree grants: framework guarantees identical to local** (urigrants.xml, traced to
   AOSP). Provider-side durability is weaker: positional account index in the document id, Drive
   data-clear, and a documented cloud-provider precedent of broken persisted grants (Nextcloud).
4. **Failure modes.** The big one: "w" truncation is provider-dependent and one report says even
   "wt"+truncate did not shrink the Drive cloud copy - stale trailing bytes corrupt an xlsx (zip
   read from EOF). Mandatory discipline: `openFileDescriptor(uri, "rwt")`, full rewrite, read back
   and verify length+hash, quarantine the mirror on mismatch. Offline writes, cloud-side conflict
   behaviour, and provider-level size limits: NOT ESTABLISHED anywhere.
5. **The named fallback does not exist.** The Drive Android app cannot sync an arbitrary local
   folder (desktop-only feature; third-party apps like Autosync exist precisely to fill it).
   Real fallbacks: manual `ACTION_SEND` share (one manual step per refresh, breaks the
   edit-in-Sheets round trip) or delete+recreate per refresh (dodges the truncate bug, but the
   Drive file id changes every time). Recommend SAF write-back primary, manual share as the
   no-grant path.

**Recommended path:** build the mirror on SAF write-back with the rwt + read-back-verify + quarantine
discipline; treat cloud arrival as eventually-consistent and unobservable.

**On-device verification on the A25 is still owed** for the whole write direction: folder
`FLAG_DIR_SUPPORTS_CREATE`, xlsx createDocument + Sheets open, shrink-rewrite via "rwt",
airplane-mode write, reboot grant reuse. Probe list in the findings file.
