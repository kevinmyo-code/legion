# SAF against the Google Drive app: findings

Answers ticket `issues/01-saf-drive-folder-feasibility.md`. Research date 2026-08-01.
Every claim is tagged `tested` / `traced` / `reasoned`. Full ledger at the end.

## VERDICT

**PARTIAL, leaning YES. Build on SAF, gate it at API 30, keep a per-file SAF fallback, add no
new OAuth scope.**

The ticket's premise is **REFUTED as a blanket statement and CONFIRMED as a version-gated one**.
The current Google Drive app (2.26.307.6, pulled and disassembled 2026-08-01) ships a full
`DocumentsProvider` that implements the tree contract, and its root advertises
`Root.FLAG_SUPPORTS_IS_CHILD` **only when `Build.VERSION.SDK_INT >= 30` and an internal runtime
feature flag is true**. That flag is exactly what AOSP `DocumentsUI` filters roots on for
`ACTION_OPEN_DOCUMENT_TREE`. So "Drive has historically not supported tree picking" is true for
Android 10 and below and false for Android 11 and above.

The crux (sub-question 2) was **traced, not tested** when this was written. **It is now TESTED and
it passed** - see `## Device probe` at the end, run 2026-08-02 on the Oppo A17K. The trace below
stands; read it as corroborated rather than provisional. The one thing the device added that the
trace did not predict is **latency**: the new file was invisible for at least 2m36s and appeared
only after the Drive app was opened. It traces to YES at both layers:

| Layer | Snapshot or live | Evidence |
|---|---|---|
| Grant | Live. `ACTION_OPEN_TREE` returns a **prefix** grant, not an enumerated list | `DocumentsUI/picker/ActionHandler.java:515-519` |
| Framework enumeration | Live. `listFiles()` runs a fresh `ContentResolver.query` per call | `androidx TreeDocumentFile.listFiles()` |
| Framework authorization | Live. Child access is re-verified per call via `isChildDocument` | `DocumentsProvider.enforceTree()` |
| Drive implementation | Live. Real `isChildDocument` + `queryChildDocuments`, cursor carries a notification URI | Drive `Lmuz;` bytecode |

There is no snapshot anywhere in the chain. **Everything above this line was written without
hardware**; the probe specified at the bottom has since been run and its results are appended under
`## Device probe`. Sub-questions 1 and 2 are settled on-device. **Sub-question 4 (offline
behaviour) and reboot persistence are still not run** - the probe reached the phone only over
Wi-Fi ADB, and both tests sever that link.

**No new OAuth scope. Do not add `drive.readonly`.** See the fallback section.

---

## 1. Does the current Drive app expose a tree-capable DocumentsProvider?

**YES on API 30+, NO on API 26-29, and Drive will not install below API 26.** `traced`

Evidence, from `com.google.android.apps.docs` 2.26.307.6 (APKPure mirror, downloaded 2026-08-01,
127,024,571 bytes, `aapt2` + `dexdump` from build-tools 36.0.0):

Manifest:

```
provider com.google.android.apps.docs.common.storagebackend.provider.StorageBackendContentProvider
  authorities        = com.google.android.apps.docs.storage
  permission         = android.permission.MANAGE_DOCUMENTS
  exported           = true
  grantUriPermissions= true
  intent-filter action android.content.action.DOCUMENTS_PROVIDER
APK minSdkVersion=26, targetSdkVersion=37
```

Class hierarchy: `StorageBackendContentProvider` extends obfuscated `Lmuz;` which extends
`android.provider.DocumentsProvider`. `Lmuz;` overrides `queryRoots`, `queryDocument`,
`queryChildDocuments`, `querySearchDocuments`, `queryRecentDocuments`, `isChildDocument`,
`openDocument`, `openTypedDocument`, `openDocumentThumbnail`, `createDocument`, `deleteDocument`,
`renameDocument`, `moveDocument`, `copyDocument`. `StorageBackendContentProvider` itself adds
`findDocumentPath` and `refresh`. `findDocumentPath` is meaningful only for tree URIs.

The root flags computation in `Lmuz;.queryRoots`, decompiled from bytecode offsets 0117-0136:

```
0117: if (!featureFlag.a()) goto 0126        // internal runtime flag
011d: v0 = Build.VERSION.SDK_INT
0121: if (v0 < 30) goto 0126
0123: v0 = 16                                // Root.FLAG_SUPPORTS_IS_CHILD == 1 << 4
0126: v0 = 0
0128: if (SDK_INT < 37) v1 = 0 else v1 = 0x200000
0133: v0 = v0 | 13                           // CREATE(1) | SUPPORTS_RECENTS(4) | SUPPORTS_SEARCH(8)
0135: v0 = v0 | v1
0136: Integer.valueOf(v0)                    // written into the root row
```

Why that single constant decides everything, `traced`:

- `DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD = 1 << 4` (AOSP `DocumentsContract.java:757`).
- `DocumentsUI/base/RootInfo.java:372` -> `supportsChildren()` returns
  `(flags & Root.FLAG_SUPPORTS_IS_CHILD) != 0`.
- `DocumentsUI/roots/ProvidersAccess.java:89` ->
  `if (state.action == State.ACTION_OPEN_TREE && !root.supportsChildren()) continue;`

So the Drive root is excluded from the folder picker on any device where that bit is clear.

**Consequences for LEGION** (`app/build.gradle.kts`: `minSdk = 24`):

| Device API | Drive in the folder picker |
|---|---|
| 24-25 | No. Drive APK requires API 26 |
| 26-29 | No. `FLAG_SUPPORTS_IS_CHILD` not set |
| 30+ | Yes, subject to the runtime feature flag |

**Caveat, `reasoned`:** the `if (!featureFlag.a())` guard is a runtime flag lookup through what
looks like a generated flag package (Phenotype shape). If it is server-controlled, Google can turn
Drive tree support off for a device or population without an app update. Treat tree support as a
**capability to probe at runtime**, never as a static assumption. This is the same shape as the old
`NavCapability` gate.

`ACTION_OPEN_DOCUMENT` (single file) is unaffected by any of the above and works on every version
Drive installs on. `traced`

---

## 2. THE CRUX: does `listFiles()` see files added AFTER the grant?

**Traced to YES at every layer. Not tested. Treat as high-confidence, not settled.**

### 2a. Framework layer: nothing snapshots

`traced`, androidx `TreeDocumentFile.listFiles()`:

```java
final Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(mUri,
        DocumentsContract.getDocumentId(mUri));
c = resolver.query(childrenUri, new String[] {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID }, null, null, null);
while (c.moveToNext()) { ... }
```

A fresh binder `query` per invocation. No field caches results. The returned `DocumentFile[]` is
constructed on the spot from that cursor.

`traced`, `DocumentsUI/picker/ActionHandler.java:515-519`:

```java
} else if (mState.action == ACTION_OPEN_TREE) {
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
}
```

`FLAG_GRANT_PREFIX_URI_PERMISSION` is a **path-prefix** grant over
`content://<auth>/tree/<treeDocId>/...`. It cannot be a snapshot of children, because it never
enumerates children. Any child URI built later shares the prefix and is covered.

`traced`, AOSP `DocumentsProvider.enforceTree()` (called on every `query`, `openFile`, `call`,
`delete`, `rename` that arrives on a tree URI):

```java
private void enforceTree(@Nullable Uri documentUri) {
    if (documentUri != null && isTreeUri(documentUri)) {
        final String parent = getTreeDocumentId(documentUri);
        final String child = getDocumentId(documentUri);
        if (Objects.equals(parent, child)) return;
        if (!isChildDocument(parent, child)) {
            throw new SecurityException("Document " + child + " is not a descendant of " + parent);
        }
    }
}
```

The authorization decision is delegated to the provider **on every call**, against the current
parent-child graph. A file created after the grant passes this check the moment it exists under the
tree. This is the normative behavior the SAF contract defines.

### 2b. Drive implementation layer: honors the contract

This is the layer that could have broken the chain. It does not, `traced` from bytecode:

`Lmuz;.isChildDocument(String,String)` is a real implementation, not the inherited
`return false` stub:

```
0000: v1 = this.b().c                 // the Drive document store
0006: v2 = store.a(parentDocId)       // resolve parent -> model object
000c: v1 = store.a(childDocId)        // resolve child  -> model object
0010: if (child == null) return false
0012: if (parent == null) return false
0015: return child.n(parent)          // real descendant test
```

`Lmuz;.queryChildDocuments` builds its cursor through a shared helper that, per call:

- builds the cursor from Drive's local document store (`Lmui;.a([String, ..., Uri)`),
- calls `Cursor.setNotificationUri(resolver, uri)`,
- puts `com.android.documentsui.extra.NOTIFICATION_URI` and
  `android.content.extra.HONORED_ARGS` into the cursor extras,
- throws `FileNotFoundException("document not found")` if the id does not resolve.

No caching, no per-grant child list, and a notification URI so observers re-query when Drive's
metadata changes.

### 2c. The real operational limits (these matter more than the contract)

1. **Drive metadata sync latency.** `reasoned`. The cursor is built from Drive's **local** store,
   not from a live network call. A statement uploaded from a browser appears to LEGION only after
   the Drive app has synced that folder's metadata to the device. Do not design a flow that assumes
   "uploaded, therefore visible now". Poll on app foreground, and expose a manual refresh.
2. **`DocumentFile.listFiles()` discards `EXTRA_LOADING`.** `traced` (androidx source above shows
   the cursor extras are never read) plus `DocumentsContract.EXTRA_LOADING`'s own doc: a provider
   may return partial results with `EXTRA_LOADING` set and notify when done. `DocumentFile` returns
   whatever the first cursor held and tells the caller nothing. Drive's helper does not set
   `EXTRA_LOADING` on this path, so the risk is low here, but the general lesson stands: for the
   ingestion loop, query the children URI **directly** with `ContentResolver` rather than through
   `DocumentFile`, so you can read `cursor.getExtras()` and fetch `document_id`, `_display_name`,
   `_size`, `last_modified` and `mime_type` in one pass instead of N binder calls per file.
   `DocumentFile` costs one IPC per attribute per file.
3. **The grant is on the tree, so a file MOVED out of the folder stops being readable**, and one
   moved in becomes readable. `reasoned` from the `isChildDocument` trace. That is the desired
   behavior here.

**Status: not `tested`. Run the probe in the last section before writing the ingestion loop.**

---

## 3. Does `takePersistableUriPermission` survive reboot and app restart?

**YES, framework-guaranteed.** `traced`

- `DocumentsUI` returns `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` for `ACTION_OPEN_TREE`
  (`picker/ActionHandler.java:518`), so the grant is takeable.
- AOSP `UriGrantsManagerService` persists granted URI permissions to
  `/data/system/urigrants.xml` (`UriGrantsManagerService.java:183`) and reloads them in
  `readGrantedUriPermissionsLocked()` at boot (line 867). Prefix grants are persisted with an
  explicit `prefix` attribute (line 897) and restored with `FLAG_GRANT_PREFIX_URI_PERMISSION`
  (line 911).
- `MAX_PERSISTED_URI_GRANTS = 512` per package (line 127); over the cap the **oldest** persisted
  grants are trimmed (line 618). LEGION needs one. Non-issue, but do not persist a grant per file.
- `DocumentsContract.Document.COLUMN_DOCUMENT_ID` doc, verbatim: "A provider must always return
  durable IDs, since they will be used to issue long-term URI permission grants when an application
  interacts with `ACTION_OPEN_DOCUMENT` and `ACTION_CREATE_DOCUMENT`."

**Drive-specific hazards that the framework guarantee does NOT cover** (`reasoned`, from the
document-id format traced in section 5):

| Event | Effect on a persisted tree grant |
|---|---|
| Reboot, app restart, app update | Survives |
| Drive app uninstalled or its data cleared | Grant becomes unusable. `takePersistable` does not resurrect a dead authority |
| Google account removed and re-added on the device | Likely breaks. The document id embeds a local account index (`acc=<n>`), not a durable account identifier |
| User revokes the grant in Settings > Apps > LEGION | Breaks, by design |

Therefore: **never assume the saved tree URI still works.** On every use, catch `SecurityException`
and `FileNotFoundException`, and re-prompt with `ACTION_OPEN_DOCUMENT_TREE`. Persist the tree URI
string in the same place LEGION persists other user config, and treat re-pick as an ordinary flow,
not an error state.

---

## 4. Can bytes be read via `openInputStream`? What about offline?

**YES for PDFs. NO for Google-native documents. Offline is a hard failure for non-cached files.**

`traced`, string constants inside `Lmuz;.openDocument`:

```
"r"                                                    supported read mode
"w" / "wt" / "rwt"                                     write modes
"Unsupported mode: "
"File is virtual: "                                    thrown for Workspace-native docs
"File not found: "
"Cannot write trashed document"
"Error opening '%s' for '%s' access: file exists, but is read only"
```

- Bank statement PDFs are ordinary binary blobs, so they open in `"r"` mode through
  `contentResolver.openInputStream(uri)`. `traced` for the code path, `reasoned` for the end-to-end.
- Google Docs / Sheets / Slides are **virtual documents**
  (`DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT = 1 << 9`, "doesn't have byte representation
  in the MIME type specified"). `openDocument` throws for them. They must go through
  `openTypedAssetFileDescriptor` with an export MIME type. `Lmuz;` does override
  `openTypedDocument`, so export is available if ever needed. **Ledger ingestion should filter to
  `application/pdf` and skip anything flagged virtual**, which sidesteps this entirely. `traced`
- **Offline: expect failure.** `reasoned`. Drive files are stream-on-demand unless the user marked
  them available offline. With no network and no cached copy, `openInputStream` will throw
  (`FileNotFoundException` or `IOException`) rather than block forever. This is not traced to the
  network layer; the `openDocument` bytecode delegates to Drive's fetch machinery which was not
  followed. **Design for it regardless**: CLAUDE.md §7 already requires graceful offline
  degradation. Wrap each file read, quarantine nothing on an IO failure, and retry on the next
  ingestion pass. An IO failure must not be recorded as "ingested".
- **Read the whole file to a private cache file before parsing.** `reasoned`. PdfBox-Android wants
  seekable input, and a Drive-backed stream over a downloading pipe is the worst case for that.
  Copy to `cacheDir`, parse, delete.

---

## 5. Per-document identity metadata

`traced` from `DocumentsContract.Document`, which is the complete and closed set of columns a
client can read:

| Column | Type | Availability | Use for the ingested-file ledger |
|---|---|---|---|
| `COLUMN_DOCUMENT_ID` (`document_id`) | String | required, durable per contract | Primary identity, but parse it. See below |
| `COLUMN_DISPLAY_NAME` (`_display_name`) | String | required | Human label only. Renameable, do not key on it |
| `COLUMN_MIME_TYPE` (`mime_type`) | String | required | Filter to `application/pdf` |
| `COLUMN_SIZE` (`_size`) | long | required column, value may be null | Change signal |
| `COLUMN_LAST_MODIFIED` (`last_modified`) | long, epoch ms | may be null if unknown | Change signal |
| `COLUMN_FLAGS` (`flags`) | int | required | Check `FLAG_VIRTUAL_DOCUMENT`, `FLAG_PARTIAL` |
| content hash | **does not exist** | n/a | Must be computed by LEGION |

**No content hash is available over SAF.** `traced`. Drive's own model carries `md5Checksum`
(the string appears in the Drive dex, it is a Drive REST field), but `DocumentsContract` has no
hash column, so it is unreachable through a tree URI. `DocumentsContract.getDocumentMetadata` only
returns provider-declared metadata for roots that set `FLAG_SUPPORTS_METADATA`, and it is an EXIF
oriented surface, not a checksum surface.

**Document id format**, `traced` from string constants in Drive's dex (`"acc="`, `"doc="`,
`"doc=encoded="`, `"enc="`, format `"%s%s;%s"`):

```
acc=<localAccountIndex>;doc=<driveFileId>
```

The `acc=` half is a **device-local** account index. The `doc=` half is the Drive file id, which is
stable across renames and moves within Drive.

### Recommended identity for the ledger

1. **Key on the Drive file id**, i.e. the substring after `doc=` in the document id. It is the only
   part that is globally stable and portable. Store the full document id too, for rebuilding URIs.
2. **Store `_size` and `last_modified`** alongside. A row whose file id matches but whose size or
   mtime changed means the statement was replaced, not re-uploaded. That is a re-ingest, and under
   CLAUDE.md §4 it must re-run the reconciliation gate, not merge.
3. **Compute a SHA-256 over the bytes LEGION actually read**, and store it. This is the only
   falsifiable identity in the whole chain (§4 rule 5's spirit: anchor to what you can verify).
   It also detects the same statement arriving under a second file id, which the Drive id cannot.
4. Do **not** key on display name. Statement files get renamed.

---

## 6. Vendor variance: Oppo A17K / ColorOS vs stock

**UNKNOWN. Must be tested on the device. Two specific things can differ.** `reasoned`

1. **The picker is `DocumentsUI`, an OEM-replaceable system app.** The root-filtering logic quoted
   in section 1 lives in `packages/apps/DocumentsUI`, not in the framework. An OEM that forks or
   substitutes it can present a different root list, restrict cloud roots, or omit the tree action.
   ColorOS ships a customized file/picker stack. Whether the Drive root survives it is not
   determinable off-device.
2. **ColorOS background restrictions.** Widely reported aggressive process killing and permission
   handling on Oppo. Relevant here because ingestion is a background-ish, potentially long
   operation over a network-backed provider. If ingestion is ever moved off the foreground, it must
   be a foreground service, and even then expect ColorOS to interfere.

Not a risk: **package visibility**. Android 11 `<queries>` filtering does not block use of a
granted content URI; no manifest `<queries>` entry for `com.google.android.apps.docs` is needed.
`reasoned`.

The A17K runs Android 12 (API 31), which clears the API 30 gate from section 1. Everything else
about it is unverified.

---

## Fallback recommendation

The ticket names two fallbacks. Ranked, with the crux question answered for each:

| Option | Files added later? | Scope cost | Android UX | Verdict |
|---|---|---|---|---|
| **SAF tree (primary)** | YES, traced at both layers | **none** | Native system picker | **Recommend** |
| SAF per-file multi-select | No, by definition. User re-picks each batch | none | Native system picker | **Recommend as the fallback** |
| (a) `drive.file` + Google Picker | **UNDOCUMENTED**, see below | non-sensitive | Poor on Android | Reject |
| (b) `drive.readonly` REST | Yes, certain | **restricted** | Fine | Reject |

**Why (a) is rejected.** Two independent reasons.

1. It does not reliably answer the crux either. Google's own scope page defines `drive.file` as
   per-file access to "files that you open with an app or that the user shares with an app while
   using the Google Picker API". The Picker exposes `allow_folder_selection`, but **neither
   `api-specific-auth` nor the Picker overview states whether a folder grant extends to files added
   to that folder afterwards.** Undocumented behavior on the exact axis this feature lives or dies
   on. `traced` (both pages read 2026-08-01, both dated 2026-07-14).
2. The Android UX is bad, from Google's own page: "The Google Picker API for desktop and mobile
   apps redirects to the Google Picker within a new tab in the user's default browser." A
   browser-tab handoff for the primary ingestion flow, versus a native system picker, is a clear
   loss.

**Why (b) is rejected**, and this is the stronger reason: `drive.readonly` is a **restricted**
scope on Google's own classification. Restricted scopes require OAuth verification plus a
recurring third-party security assessment. LEGION already has an unresolved clone-and-run blocker
because the Drive OAuth client is keyed to package + SHA-1 signing cert (`memory/MEMORY.md`,
Blocking). A restricted scope makes a stranger's own build strictly harder to authorize, not
easier, and drags a solo portfolio project into an annual audit. This directly violates CLAUDE.md
§7's clone-and-run checklist item. **Do not add it.**

### The recommended shape

```
LedgerSourceCapability                     // the NavCapability pattern, reused
  tree picking available  = SDK_INT >= 30 && ACTION_OPEN_DOCUMENT_TREE resolves
                            && the user actually finds a Drive root in the picker

Primary : ACTION_OPEN_DOCUMENT_TREE  -> takePersistableUriPermission(READ)
          -> persist tree URI -> poll children on foreground + manual refresh
Fallback: ACTION_OPEN_DOCUMENT (multi-select, application/pdf)
          -> takePersistableUriPermission per file
          -> user re-picks when new statements land
```

The fallback needs no new code path downstream: both produce document URIs that
`contentResolver.openInputStream` reads identically. Only the discovery half differs. Ship the
fallback in the same commit as the primary, because section 1 proves a meaningful slice of devices
(API 26-29, plus any device where Drive's runtime flag is off, plus possibly ColorOS) will never
see a Drive root.

**Zero new OAuth scopes. `drive.appdata` in `sync/DriveAuth.kt` is untouched.**

---

## The minimal experiment that settles #2

Sub-question 2 is `traced`, not `tested`. The Drive-implementation layer traced clean, but the
end-to-end has never run. This probe settles it in about 15 minutes.

**Do NOT stand up an emulator.** A Google Play system image is a multi-GB download, needs a Play
Store sign-in with a real Google account inside the emulator, and Drive is not preinstalled on
Play images. The Oppo A17K already has Drive, the account, and working ADB. Use it.

### Setup

| Item | Value |
|---|---|
| Device | Oppo A17K, ColorOS, Android 12 (API 31). Clears the API 30 gate and answers sub-question 6 in the same run |
| Account | The Google account already signed into the Drive app on that phone |
| Drive prep | Create folder `LegionProbe` in My Drive. Put two PDFs in it. Open the Drive app once and browse into the folder so its metadata is synced locally |
| Probe app | New empty Compose-free project, package `com.kevin.safprobe`, minSdk 30. **Do not build it inside the legion repo.** |

### Probe code, paste-ready

`MainActivity.kt`:

```kotlin
package com.kevin.safprobe

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout

class MainActivity : Activity() {

    private var tree: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pick = Button(this).apply {
            text = "PICK TREE"
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 1
                )
            }
        }
        val list = Button(this).apply {
            text = "LIST"
            setOnClickListener { dump() }
        }
        val reread = Button(this).apply {
            text = "RELOAD PERSISTED"
            setOnClickListener {
                tree = contentResolver.persistedUriPermissions
                    .firstOrNull()?.uri
                Log.i(TAG, "persisted tree = $tree")
                dump()
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pick); addView(list); addView(reread)
        })
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        tree = uri
        Log.i(TAG, "TREE=$uri")
        dump()
    }

    private fun dump() {
        val t = tree ?: run { Log.w(TAG, "no tree"); return }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            t, DocumentsContract.getTreeDocumentId(t)
        )
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )
        var n = 0
        val c: Cursor? = contentResolver.query(children, cols, null, null, null)
        Log.i(TAG, "extras=" + c?.extras)
        c?.use {
            while (it.moveToNext()) {
                n++
                Log.i(
                    TAG,
                    "id=${it.getString(0)} name=${it.getString(1)} " +
                        "mime=${it.getString(2)} size=${it.getLong(3)} " +
                        "mtime=${it.getLong(4)} flags=${it.getInt(5)}"
                )
            }
        }
        Log.i(TAG, "COUNT=$n")

        // byte read of the first PDF
        val c2 = contentResolver.query(children, cols, null, null, null)
        c2?.use {
            while (it.moveToNext()) {
                if (it.getString(2) != "application/pdf") continue
                val doc = DocumentsContract.buildDocumentUriUsingTree(t, it.getString(0))
                try {
                    val bytes = contentResolver.openInputStream(doc)!!
                        .use { s -> s.readBytes() }
                    Log.i(TAG, "READ ok ${it.getString(1)} bytes=${bytes.size} " +
                        "header=${String(bytes.copyOfRange(0, 5))}")
                } catch (e: Exception) {
                    Log.e(TAG, "READ failed ${it.getString(1)}", e)
                }
                break
            }
        }
    }

    companion object { const val TAG = "SAFPROBE" }
}
```

Manifest needs nothing beyond a default launcher activity. No permissions.

### Steps and observables

| # | Step | PASS observable | FAIL observable |
|---|---|---|---|
| 1 | Install, tap PICK TREE | Picker opens, **"Drive" appears in the left drawer as a root** | No Drive root. Sub-question 1 fails on ColorOS despite API 31. Stop, take the per-file fallback |
| 2 | Navigate into `LegionProbe`, tap "Use this folder", allow | `TREE=content://com.google.android.apps.docs.storage/tree/...` in logcat | Drive root visible but not selectable |
| 3 | Read the LIST output | `COUNT=2`, two `id=acc=...;doc=...` lines, `mime=application/pdf`, non-zero size and mtime | `COUNT=0`. Check `extras=` for a `loading` key before concluding failure |
| 4 | Read the byte-read output | `READ ok ... header=%PDF-` | `READ failed`. Section 4 offline hazard is worse than modelled |
| 5 | **From a laptop browser**, upload a third PDF into `LegionProbe`. Do not touch the phone's Drive app | | |
| 6 | Wait 30s, tap LIST | **`COUNT=3`. THIS IS THE CRUX PASSING.** | `COUNT=2`. Open the Drive app, browse to the folder, return, tap LIST again |
| 6b | If 6 needed the Drive app opened to reach 3 | Crux passes but with a **sync-latency caveat**. Record the workaround needed | `COUNT` stays 2 forever: crux FAILS, go to the fallback section |
| 7 | Force-stop the probe, reboot the phone, launch, tap RELOAD PERSISTED | `persisted tree = content://...` and `COUNT=3` | Sub-question 3 fails on ColorOS |
| 8 | Enable airplane mode, tap LIST | Listing still returns 3 from local metadata | |
| 9 | Still offline, observe the byte read | Whatever it does, record it. This is the offline answer sub-question 4 could not trace | |

Logcat filter: `adb logcat -s SAFPROBE`.

Step 6 is the whole experiment. Steps 7-9 are free once the app is installed.

---

## Citations

| Source | URL | Date |
|---|---|---|
| androidx `TreeDocumentFile.java` (`listFiles()`) | https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/documentfile/documentfile/src/main/java/androidx/documentfile/provider/TreeDocumentFile.java | read 2026-08-01, androidx-main |
| AOSP `DocumentsProvider.java` (`isChildDocument`, `enforceTree`) | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/DocumentsProvider.java | read 2026-08-01, main |
| AOSP `DocumentsContract.java` (Root/Document flags and columns) | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/DocumentsContract.java | read 2026-08-01, main |
| AOSP `UriGrantsManagerService.java` (urigrants.xml, 512 cap, prefix persistence) | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/uri/UriGrantsManagerService.java | read 2026-08-01, main |
| DocumentsUI `roots/ProvidersAccess.java` (root filter for ACTION_OPEN_TREE) | https://android.googlesource.com/platform/packages/apps/DocumentsUI/+/refs/heads/main/src/com/android/documentsui/roots/ProvidersAccess.java | read 2026-08-01, main |
| DocumentsUI `base/RootInfo.java` (`supportsChildren()`) | https://android.googlesource.com/platform/packages/apps/DocumentsUI/+/refs/heads/main/src/com/android/documentsui/base/RootInfo.java | read 2026-08-01, main |
| DocumentsUI `picker/ActionHandler.java` (prefix + persistable grant) | https://android.googlesource.com/platform/packages/apps/DocumentsUI/+/refs/heads/main/src/com/android/documentsui/picker/ActionHandler.java | read 2026-08-01, main |
| Android docs, Open files using the Storage Access Framework | https://developer.android.com/guide/topics/providers/document-provider | page dated 2025-05-07 |
| Google, Choose Google Drive API scopes (scope classification) | https://developers.google.com/workspace/drive/api/guides/api-specific-auth | page dated 2026-07-14 |
| Google, Google Picker overview for desktop and mobile apps | https://developers.google.com/workspace/drive/picker/guides/overview-desktop | page dated 2026-07-14 |
| Google Drive Android APK `com.google.android.apps.docs` 2.26.307.6 | APKPure mirror `d.apkpure.com/b/APK/com.google.android.apps.docs?version=latest` | downloaded 2026-08-01, 127,024,571 bytes |

Leads that were checked and did NOT corroborate anything load-bearing, recorded so nobody
re-walks them:

- Issue Tracker 65673235 and 135636079 both look on-topic by title but require sign-in; content
  unreadable. Not cited.
- CommonsWare, "Scoped Storage Stories: Trees", 2019-11-09: says only "few cloud storage providers
  seem to support `ACTION_OPEN_DOCUMENT_TREE`". Generic, six years stale, no Drive-specific claim.
  **This class of source is exactly why the ticket's premise looked settled and was not.**
- General web search returned nothing dated 2023 or later that speaks to Drive's SAF tree support
  either way. **The disassembly is the only current evidence that exists.** Anyone revisiting this
  should re-run the APK check against the then-current Drive version rather than trust this file.

---

## Assumptions ledger

| # | Claim | Tag |
|---|---|---|
| 1 | Drive 2.26.307.6 ships `StorageBackendContentProvider`, authority `com.google.android.apps.docs.storage`, with a `DOCUMENTS_PROVIDER` intent filter | `traced` (aapt2 manifest dump) |
| 2 | Drive's provider sets `Root.FLAG_SUPPORTS_IS_CHILD` only when `SDK_INT >= 30` and a runtime feature flag is true | `traced` (dexdump of `Lmuz;.queryRoots` offsets 0117-0136) |
| 3 | The zero-branch register (v17) at offset 0126 holds 0, i.e. the flags are `13 | (16 or 0) | (0x200000 or 0)` | `reasoned` (register reuse not fully followed; the structure is unambiguous, the literal 0 is not) |
| 4 | That runtime flag may be server-controllable, so tree support must be probed not assumed | `reasoned` |
| 5 | `DocumentsUI` excludes roots without `FLAG_SUPPORTS_IS_CHILD` from `ACTION_OPEN_TREE` | `traced` (`ProvidersAccess.java:89`, `RootInfo.java:372`) |
| 6 | Drive APK minSdk 26, so API 24-25 devices have no Drive app at all | `traced` (manifest) |
| 7 | `DocumentFile.listFiles()` is a live per-call `ContentResolver.query`, never cached | `traced` (androidx source) |
| 8 | `ACTION_OPEN_TREE` yields a PREFIX + PERSISTABLE grant, not an enumerated child list | `traced` (`ActionHandler.java:515-519`) |
| 9 | Every tree-URI operation re-checks descendancy through the provider's `isChildDocument` | `traced` (`DocumentsProvider.enforceTree`) |
| 10 | Drive implements `isChildDocument` for real (resolves both ids, calls a descendant test) | `traced` (dexdump) |
| 11 | Therefore files added after the grant are enumerable and readable | `traced` at the contract and implementation layers, **NOT `tested` end-to-end** |
| 12 | Visibility of a newly uploaded file is bounded by Drive's local metadata sync, not by the grant | `reasoned` (cursor is built from Drive's local store; latency never measured) |
| 13 | `DocumentFile` discards `EXTRA_LOADING`; query the children URI directly instead | `traced` (androidx source never reads cursor extras) |
| 14 | Persisted URI grants survive reboot via `/data/system/urigrants.xml`, cap 512 per package | `traced` (`UriGrantsManagerService`) |
| 15 | A persisted Drive tree grant can still break on account removal / Drive data clear, so always handle re-pick | `reasoned` |
| 16 | PDFs open in `"r"` mode; Workspace-native docs throw "File is virtual:" and need `openTypedDocument` | `traced` (openDocument string constants) |
| 17 | Offline reads of non-cached files fail rather than hang | `reasoned` (not traced into Drive's fetch layer) |
| 18 | SAF exposes no content hash; Drive's `md5Checksum` is unreachable through a tree URI | `traced` (`DocumentsContract.Document` column set is closed) |
| 19 | Drive document ids are `acc=<localAccountIndex>;doc=<driveFileId>` | `traced` (dex string constants + format `"%s%s;%s"`) |
| 20 | Ledger identity should be the Drive file id plus a LEGION-computed SHA-256, with size/mtime as change signals | `reasoned` (design recommendation) |
| 21 | ColorOS may fork DocumentsUI and change the root list; unknown either way | `reasoned` |
| 22 | No `<queries>` manifest entry is needed for granted content URIs | `reasoned` |
| 23 | `drive.file` folder-grant semantics for later-added files are undocumented by Google | `traced` (both owning pages read, neither states it) |
| 24 | The Android Google Picker redirects to a browser tab | `traced` (Google's own page, dated 2026-07-14) |
| 25 | `drive.readonly` is a restricted scope and worsens the clone-and-run blocker | `traced` for the classification, `reasoned` for the impact |
| 26 | Nothing in this document was run on a device | `tested` (`adb devices` empty; no emulator image installed) |

---

## Device probe

Run 2026-08-02 00:11-00:22 local, ticket 11. **Everything below is `tested` unless tagged
otherwise.** It supersedes the `traced` tags above wherever the two speak to the same claim.
Line 26 of the assumptions ledger ("Nothing in this document was run on a device") is now false
for this section only; the sections above it were still authored without hardware.

### Setup as actually run

| Item | Planned | Actual |
|---|---|---|
| Device | Oppo A17K, Android 12 (API 31) | `OPPO CPH2471`, `SDK_INT=31`, ColorOS `V12.1`. Matches |
| Transport | USB | **Wireless debugging.** USB never enumerated: the phone did not appear in `Get-PnpDevice` at all, so `adb devices` stayed empty through a cable swap and an MTP-mode change. Paired over Wi-Fi instead |
| Drive app | 2.26.307.6 (the version disassembled) | **2.26.297.3**, i.e. slightly OLDER than the APK the trace was taken from. The disassembled behaviour held anyway |
| Folder | `LegionProbe`, two PDFs | `LegionProbe`, **five** real BofA statement PDFs |
| Probe | research file's paste-ready code | Same, plus null projection and on-screen output. See ticket 11 |

### Results

| Step | Observable | Result |
|---|---|---|
| 1 | Drive offered as a root in the tree picker | **PASS.** "Drive / kevinmyo@gmail.com" in the drawer |
| 2 | Folder selectable, persistable grant taken | **PASS.** `TREE=content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3Dencoded%3D...` |
| 3 | `listFiles()` returns the folder's files | **PASS.** `COUNT=5`, all `application/pdf`, non-zero sizes |
| 4 | Byte read works | **PASS.** `READ ok ... bytes=164087 header=%PDF- ms=637` |
| 6 | **New file appears without re-picking** | **PASS, with a latency caveat.** See below |

**Sub-question 1 is settled YES on this device.** Drive's provider does advertise
`FLAG_SUPPORTS_IS_CHILD` at API 31 and DocumentsUI does admit it to `ACTION_OPEN_DOCUMENT_TREE`.
Assumptions 2 and 5 hold at runtime, on a Drive build one version older than the one dexdumped.

**Sub-question 2 (the crux) is settled YES, qualified.** A sixth PDF uploaded from a laptop browser
did appear in `listFiles()` through the existing grant, with no re-pick. The qualification is
timing, and it is step 6b of the plan, not step 6:

| Time | Event | `COUNT` |
|---|---|---|
| 00:14:44 | Grant taken | 5 |
| 00:16:12 | Sixth PDF uploaded from laptop browser (its own `last_modified`) | - |
| 00:16:50 | LIST, phone's Drive app not touched | **5** |
| 00:18:15 | LIST | **5** |
| 00:18:48 | LIST, 2m36s after upload | **5** |
| ~00:19-00:20 | **Drive app opened on the phone** and the folder browsed | - |
| 00:22:00 | LIST | **6** |

So: **not visible for at least 2 minutes 36 seconds on its own, visible after the Drive app was
opened.** The two variables (elapsed time, Drive app foregrounded) were not isolated - this run
cannot say whether waiting longer alone would have sufficed. Treat the access model as sound but
**do not design as though a fresh upload is visible promptly.** A "scan folder" action that finds
nothing new is an expected outcome, not an error state, and the UI must say so rather than imply
the folder is empty or the grant is broken.

### Metadata actually returned, and what it costs ticket 03

Columns, from a **null projection** so nothing is hidden:

```
_id, document_id, _display_name, _size, mime_type, flags, last_modified, icon
```

1. **There is no hash column, confirmed on hardware.** The ingested-file ledger cannot key on
   content identity from SAF metadata. Either hash the bytes locally after `openInputStream`, or
   key on `document_id`.
2. **`document_id` is opaque, stable within a session, and equal to `_id`.** Shape is
   `acc=1;doc=encoded=<base64ish>`. The `acc=1` prefix is an account index, which is a hazard for
   a multi-account phone: it is positional, not an account identifier. **`reasoned`** - one account
   was signed in, so nothing here tests what a second one does to the prefix.
3. **`last_modified` is a real per-file upload timestamp.** The first five all read
   `1785646789365` (2026-08-01 23:59:49), which looked like a folder-wide stamp and would have
   been useless. The sixth read `1785647772220` (2026-08-02 00:16:12), matching its upload. The
   uniformity was a batch upload, nothing more. It is upload time, **not** the statement's own
   date, so it orders ingestion but says nothing about the document's content.
4. **`flags=455` on every file** = `MOVE|COPY|RENAME|DELETE|WRITE|THUMBNAIL`
   (256+128+64+4+2+1). Two things follow. `FLAG_VIRTUAL_DOCUMENT` (512) is **absent**, so PDFs are
   real byte streams and `openInputStream` is correct for them. But a Google-native Doc or Sheet
   dropped in the same folder **would** be virtual and would fail that call - ingestion must skip
   non-`application/pdf` entries rather than assume every child is readable. **`reasoned`**: no
   Google-native file was placed in the folder to confirm it.

### Two hazards the trace did not predict

1. **An empty listing is indistinguishable from an unloaded one.** `extras` came back
   `Bundle[EMPTY_PARCEL]` on every query - **no `loading` key, ever**, contrary to what step 3 of
   the plan expected to look for. Yet the picker itself showed "No items" for both My Drive and
   `LegionProbe` on first entry, then populated on a pull-to-refresh. So the provider does serve
   stale-empty results while it loads, and gives the caller **no signal at all** that it is doing
   so. A batch ingest must not conclude "folder is empty" from a single `COUNT=0`.
2. **First read of a not-yet-cached file is slow and needs the network.** 637ms for an already
   browsed file, **1248ms for the freshly uploaded one**. Sixty files at that rate is a minute of
   pure I/O before any parsing or LLM call, which is a real input to ticket 05's execution model.

### Not run

Steps 7 (reboot persistence), 8 and 9 (airplane-mode listing and byte read) were **not executed**.
Both are blocked by the transport: the phone is reachable only over Wi-Fi ADB, so enabling airplane
mode severs the connection, and a reboot drops wireless debugging on most ColorOS builds. They need
either a working USB cable or a human reading the on-screen output. **The offline failure mode
therefore remains untested**, and sub-question 4 stays `traced`.

### Assumptions ledger for this section

| # | Claim | Tag |
|---|---|---|
| D1 | Drive is offered as a pickable tree root on API 31 / Drive 2.26.297.3 | `tested` |
| D2 | A file added after the grant is returned by `listFiles()` with no re-pick | `tested` |
| D3 | That file was NOT returned for at least 2m36s, and appeared only after the Drive app was opened | `tested` |
| D4 | Whether elapsed time alone would have sufficed | **unknown, not isolated** |
| D5 | The provider exposes no hash-like column | `tested` (null projection) |
| D6 | `last_modified` is per-file upload time | `tested` (two distinct values, one matching a known upload) |
| D7 | `flags=455`, so PDFs are non-virtual | `tested` for the value, arithmetic for the decode |
| D8 | Google-native files in the same folder would be virtual and fail `openInputStream` | `reasoned` |
| D9 | `acc=1` is a positional account index that a second signed-in account could disturb | `reasoned` |
| D10 | `extras` never carries a `loading` key, yet stale-empty listings occur | `tested` |
| D11 | Reboot persistence and offline behaviour | **not run** |
