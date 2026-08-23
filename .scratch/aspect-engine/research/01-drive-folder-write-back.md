# SAF write-back into a Google Drive folder: findings

Answers ticket `issues/01-drive-folder-write-back.md`. Research date 2026-08-23.
Builds on `.scratch/ledger-drive-ingestion/research/01-saf-drive-folder-findings.md` (the 2026-08-01
Drive APK disassembly + 2026-08-02 device probe), which is treated as a primary source here - it is
the only current evidence on the Drive provider's flags and is more specific than anything public.

## VERDICT

**WORKS-WITH-CAVEATS.** The Drive documents provider advertises create and write, implements
`createDocument` and the write open modes, and independent reports show written bytes reaching the
cloud. But upload timing is undocumented, `"w"` mode truncation is provider-dependent with a
credible report that even truncate did not shrink the cloud copy, and offline/conflict behaviour is
documented nowhere. For an .xlsx (a zip - trailing stale bytes corrupt it), the write path must be:
`openFileDescriptor(uri, "rwt")`, write the full file, read back and verify length/hash, quarantine
the mirror on mismatch. The named fallback (local folder + Drive app folder-sync) **does not exist
on Android**: the Google Drive Android app cannot sync an arbitrary local folder.

On-device verification on the A25 is still owed for every claim below tagged `traced` or
`reasoned`; nothing here was run on hardware for the write direction.

---

## 1. Does the provider advertise create/write, and on what versions?

**YES**, from the strongest available source - the repo's own disassembly of Drive
`com.google.android.apps.docs` 2.26.307.6 (2026-08-01) and the device probe on 2.26.297.3
(2026-08-02):

- **Root flags include `FLAG_SUPPORTS_CREATE`**: `queryRoots` computes `flags | 13` =
  `CREATE(1) | SUPPORTS_RECENTS(4) | SUPPORTS_SEARCH(8)` on every version
  (ledger research file, section 1 bytecode trace).
- **The provider implements the full mutation surface**: `createDocument`, `deleteDocument`,
  `renameDocument`, `moveDocument`, `copyDocument` are all real overrides in the `Lmuz;` class
  (same trace).
- **`openDocument` accepts write modes**: string constants `"w"`, `"wt"`, `"rwt"` sit next to
  `"Unsupported mode: "` and `"Cannot write trashed document"` in the dex (same trace, section 4).
- **On-device, every file in the probed folder carried `flags=455`**, which includes
  `FLAG_SUPPORTS_WRITE (2)` and `FLAG_SUPPORTS_DELETE (4)` (device probe, metadata section).
- **`FLAG_DIR_SUPPORTS_CREATE` on the folder document row itself was NOT directly observed** - the
  probe listed the folder's children (files), not the folder's own flags row. Given the root-level
  CREATE flag and the implemented `createDocument`, per-folder create is `reasoned`, not `tested`.
  The A25 probe should query the tree document's own `COLUMN_FLAGS` and confirm bit 8.
- Version gating: only tree-picking is gated (API 30+ plus a runtime flag). Write support carries
  no version gate in the traced flags computation. Drive requires API 26+ to install.

Generic contract context: `COLUMN_FLAGS` capabilities (`FLAG_SUPPORTS_WRITE`,
`FLAG_DIR_SUPPORTS_CREATE`) and `ACTION_CREATE_DOCUMENT` are defined at
https://developer.android.com/guide/topics/providers/document-provider and
https://developer.android.com/training/data-storage/shared/documents-files .

## 2. Does a written file actually upload, and when?

**Uploads happen; timing is NOT ESTABLISHED from any official source.** Google documents nothing
about when the Drive provider pushes bytes written through SAF.

- Positive report: an App Inventor community developer writing records into a Drive file through
  SAF saw the change "instantly updated on my PC"
  (https://community.appinventor.mit.edu/t/saf-app-inventor-implementation-of-storage-access-framework/41603?page=22).
  One anecdote, on Wi-Fi, small text file. Not a guarantee.
- Mechanism, `reasoned` from the ledger disassembly: the provider serves reads from Drive's
  **local** document store and cursor queries never hit the network, so a write almost certainly
  lands in that local store and is uploaded by the Drive app's own sync machinery on its schedule.
  The download direction measured a 2m36s+ staleness window that cleared only when the Drive app
  was opened (device probe). Assume the upload direction can lag the same way; "written" is not
  "in the cloud".
- **Staleness window on read-back**: same mechanism, symmetric. A read through the provider after a
  cloud-side edit returns the local store's bytes until Drive syncs. No documented bound.
- Community threads show uploads can wedge entirely ("waiting to upload",
  https://support.google.com/drive/thread/244029430/android-google-drive-stuck-waiting-to-upload).
  User-level, not developer docs, but it is a real observed failure state.

Design consequence: the mirror write must be considered complete when the provider write returns
and the local read-back verifies; cloud arrival is asynchronous and unobservable through SAF.

## 3. Persistable URI permissions across reboots - same as local?

**At the framework layer, YES - identical guarantees.** Grants persist in
`/data/system/urigrants.xml` and reload at boot, prefix grants included, 512 per package
(traced in the ledger research file against AOSP `UriGrantsManagerService.java`;
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/uri/UriGrantsManagerService.java).

**At the provider layer, weaker than local**, because persistence only helps if the provider's
document ids stay durable:

- Drive's document id embeds a positional local account index (`acc=1;doc=...`) - a second
  signed-in account or account re-add can disturb it (ledger research, `reasoned`, D9).
- Drive app data clear or uninstall kills the authority; `takePersistableUriPermission` does not
  resurrect it (ledger research, section 3).
- Precedent that cloud providers break persisted grants in practice:
  https://github.com/nextcloud/android/issues/8336 (persisted tree URI invalid after restart on
  Nextcloud's provider). Drive was NOT the provider there; cited as the failure class, not as a
  Drive fact.
- Reboot persistence of a Drive tree grant was **never run on hardware** (ledger probe steps 7-9
  blocked by wireless-ADB transport). Still owed, now on the A25.

Handling is unchanged from the ledger design: catch `SecurityException`/`FileNotFoundException` on
every use, re-prompt with `ACTION_OPEN_DOCUMENT_TREE` as an ordinary flow.

## 4. Known failure modes

1. **`"w"` mode may not truncate - and for .xlsx that is corruption, not staleness.**
   `ContentResolver.openOutputStream` documents that mode behaviour is per-provider; CommonsWare's
   guidance is that plain `"w"` may "overwrite parts of the content" and to use `"rwt"` for
   guaranteed truncate ("wt" is undocumented as an openOutputStream mode)
   (https://commonsware.com/AndExplore/pages/chap-t29-005.html,
   https://learn.microsoft.com/en-us/dotnet/api/android.content.contentresolver.openoutputstream?view=net-android-35.0).
   The same class of bug is fixed in react-native-fs by switching modes
   (https://github.com/itinance/react-native-fs/pull/837). Against Drive specifically, one
   developer reported that even `"wt"` plus `FileChannel.truncate(0)` did not shrink the file when
   the new content was smaller (App Inventor thread above). An xlsx is a zip whose central
   directory is located from the END of the file; stale trailing bytes make Sheets reject it.
   **Mitigation is mandatory**: `"rwt"` via `openFileDescriptor`, then read back and compare
   length + SHA-256 to what was written; mismatch quarantines the mirror and reports it (§4
   posture applied to the write direction).
2. **Offline writes: NOT ESTABLISHED.** No documentation of whether a provider write with no
   network succeeds into the local store and queues, or throws. Drive reads of uncached files were
   `reasoned` to fail offline and never tested. The A25 probe must cover an airplane-mode write.
3. **Conflict when the cloud copy changed since the last sync: NOT ESTABLISHED.** Nothing in
   Android docs, Drive docs, or credible reports states whether the provider last-write-wins,
   forks a "conflicted copy", or errors. Assume last-write-wins (same assumption already forced
   on `sync/` by Drive's missing compare-and-swap, CLAUDE.md §2 finding 2). Mitigation: the mirror
   is generated output, never the source of truth - a lost write costs a regeneration, not data.
4. **File size limits at the provider level: NOT ESTABLISHED.** Drive-the-service caps uploads at
   5 TB per file (https://support.google.com/drive/answer/2424368), irrelevant at xlsx scale. No
   documented provider-level cap; a ledger-mirror xlsx is a few hundred KB, `reasoned` non-issue.
5. **Upload can wedge** ("waiting to upload" states, support thread above) with no signal readable
   through SAF. The app cannot detect it; only the user can, in the Drive app.

## 5. Fallback assessment

**The ticket's named fallback does not exist.** The Google Drive Android app has no
folder-sync/backup of arbitrary local folders - that is a Drive for Desktop feature. Confirmed by
Google Drive Community threads
(https://support.google.com/drive/thread/232356962/can-you-sync-android-folders-with-google-drive)
and by the entire existence of third-party fillers (Autosync,
https://play.google.com/store/apps/details?id=com.ttxapps.drivesync; TechRepublic walkthrough,
https://www.techrepublic.com/article/configure-android-folders-to-auto-sync-with-google-drive/).

Remaining fallbacks, ranked:

| Option | Audit-and-edit loop cost | Verdict |
|---|---|---|
| **SAF write-back (primary)** | None: file appears in Drive/Sheets on Drive's sync schedule | Recommend, with the rwt + read-back-verify discipline |
| Local file + third-party sync app (Autosync/FolderSync) | User installs and configures an unrelated paid-tier app; LEGION cannot see sync state; violates the spirit of clone-and-run (setup a stranger will not do) | Reject as a designed dependency; note it exists for a power user |
| Local-only + manual share (`ACTION_SEND` to Drive) | One manual step per mirror refresh; user must overwrite the old copy by hand or accumulate copies; edit-in-Sheets round-trip breaks | Acceptable last resort; keep as the no-grant path |
| Delete + `createDocument` per refresh instead of in-place write | Sidesteps the truncate bug entirely, BUT the Drive file id changes every refresh, breaking Sheets bookmarks/recents and any shared link | Use only if in-place `"rwt"` write fails on-device |

Cost summary for the loop: with SAF write-back the loop is intact but eventually-consistent (edits
land in Sheets when Drive syncs). Every fallback except the third-party app breaks either the
"same file" property or the "no manual step" property.

## A25 probe still owed (all write-direction claims are untested)

1. Folder's own `COLUMN_FLAGS` carries `FLAG_DIR_SUPPORTS_CREATE (8)`.
2. `DocumentsContract.createDocument` with an xlsx MIME in the picked tree; file appears in Drive
   web, measure latency, with and without opening the Drive app.
3. Rewrite smaller content via `openFileDescriptor(uri, "rwt")`; verify cloud copy shrinks and the
   xlsx opens in Sheets.
4. Airplane-mode write; observe throw vs queue, and what happens on reconnect.
5. Reboot; reuse the persisted tree grant for a write (carried over unrun from the ledger probe).

---

## Assumptions ledger

| # | Claim | Tag |
|---|---|---|
| 1 | Drive root advertises `FLAG_SUPPORTS_CREATE`; provider implements createDocument/delete/rename/move/copy; write modes "w"/"wt"/"rwt" accepted | `traced` (repo disassembly of Drive 2.26.307.6, ledger research file) |
| 2 | Files in a Drive tree carry `FLAG_SUPPORTS_WRITE` (`flags=455`) | `traced` (2026-08-02 device probe, other hardware; A25 unrun) |
| 3 | `FLAG_DIR_SUPPORTS_CREATE` on the folder row itself | `reasoned` (implied by 1; never queried) |
| 4 | Written bytes reach the cloud | `traced` (one independent report, App Inventor thread) |
| 5 | Upload timing and read-back staleness window | NOT ESTABLISHED; `reasoned` symmetric with the measured 2m36s+ download lag |
| 6 | Framework persistence of tree grants across reboot equals local | `traced` (AOSP UriGrantsManagerService) |
| 7 | Provider-side grant durability weaker: account index, data clear, cloud-provider precedent | `traced` (ledger research; nextcloud/android#8336 for the class) |
| 8 | `"w"` truncation is provider-dependent; `"rwt"` is the reliable mode | `traced` (CommonsWare; openOutputStream docs) |
| 9 | Even truncate may fail to shrink the Drive cloud copy | `traced` (single user report, App Inventor thread; uncorroborated) |
| 10 | Stale trailing bytes corrupt an xlsx | `reasoned` (zip central directory read from EOF) |
| 11 | Offline write behaviour | NOT ESTABLISHED |
| 12 | Cloud-side conflict behaviour | NOT ESTABLISHED |
| 13 | Provider-level size limit | NOT ESTABLISHED; service cap 5 TB `traced` (Drive help) |
| 14 | Drive Android app cannot sync arbitrary local folders | `traced` (Drive Community threads; third-party apps exist to fill it) |
| 15 | Nothing in this document was run on the A25 | true by construction |
