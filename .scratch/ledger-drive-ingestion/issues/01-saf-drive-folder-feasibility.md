---
map: ledger-drive-ingestion
ticket: 01
title: "Can SAF actually read a Google Drive folder?"
type: research
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Can SAF actually read a Google Drive folder?

## Question

The standing preference is to reach the statements folder through Android's
`ACTION_OPEN_DOCUMENT_TREE` against the Google Drive app's DocumentsProvider, avoiding any new
OAuth scope. **This whole effort's access model depends on it working, and it is genuinely
uncertain.** Google Drive's SAF provider has historically been limited, and at points has not
supported tree picking at all.

Establish, against primary sources and ideally a real device check:

1. Does the current Google Drive Android app expose a DocumentsProvider that supports
   `ACTION_OPEN_DOCUMENT_TREE` (folder picking), or only `ACTION_OPEN_DOCUMENT` (single file)?
2. If a tree can be picked, can `DocumentFile.listFiles()` enumerate its children, and does the
   listing include files **added after** the grant? This is the crux: statements get uploaded to
   the folder later, and a snapshot-at-pick-time grant is useless here.
3. Does `takePersistableUriPermission` survive reboot and app restart for a Drive-backed tree?
4. Can file **bytes** be read through `contentResolver.openInputStream` for a Drive-backed
   document, given Drive files may be stream-on-demand rather than resident on device? What
   happens offline?
5. What identity metadata is available per document: a stable document id, size, last-modified,
   and is any content hash exposed? (Feeds the ingested-file ledger's identity choice.)
6. Vendor variance: does this differ on an Oppo A17K with ColorOS versus stock Android?

**If the answer is no, or partial**, state precisely which of the two rejected alternatives is
then correct and why: `drive.file` plus the Google Picker (non-sensitive scope, but the same
files-added-later question applies), or `drive.readonly` REST (certain, but a restricted scope
that worsens the clone-and-run blocker in `memory/MEMORY.md`).

Deliver a findings file with primary-source citations, and tag every claim `tested` (a real device
or emulator check) versus `reasoned` (inferred from docs). A docs-only answer to question 2 is not
sufficient to build on.

## Answer

**PARTIAL, leaning YES. Build on SAF, gate it at API 30, keep a per-file SAF fallback, add no new
OAuth scope.** Full findings with citations: `../research/01-saf-drive-folder-findings.md`.

1. **Tree-capable provider: yes, but version-gated.** The current Drive app (2.26.307.6, pulled and
   disassembled) ships a real `DocumentsProvider` whose root advertises `Root.FLAG_SUPPORTS_IS_CHILD`
   only when `SDK_INT >= 30` and an internal runtime flag is set. AOSP `DocumentsUI` filters roots on
   exactly that flag for `ACTION_OPEN_DOCUMENT_TREE`. So the ticket's premise is REFUTED as a blanket
   claim and CONFIRMED for Android 10 and below. `traced`.
2. **THE CRUX - files added after the grant: traces to YES, and is NOT device-verified.** No snapshot
   exists anywhere in the chain: the grant is a prefix grant, `listFiles()` re-queries per call,
   `enforceTree()` re-authorizes per call, and Drive implements real `isChildDocument` /
   `queryChildDocuments`. All four layers `traced`; none `tested`. **This is the one claim the whole
   access model rests on and it remains unproven on hardware.**
3. **Persisted permission survives reboot**, subject to the usual revocation cases (account removed,
   Drive data cleared). Handle re-pick. `traced` / `reasoned`.
4. **Bytes readable** via `openInputStream` in `"r"` mode for PDFs. Workspace-native docs are virtual
   and need `openTypedDocument` - irrelevant here, statements are PDFs. Offline reads of non-cached
   files fail rather than hang. `traced` / `reasoned`.
5. **No content hash is exposed.** `DocumentsContract.Document`'s column set is closed and Drive's
   `md5Checksum` is unreachable through a tree URI. Document ids take the form
   `acc=<localAccountIndex>;doc=<driveFileId>`. **This directly constrains the ingested-file ledger:
   identity must be a LEGION-computed hash, with the Drive file id plus size/mtime as change
   signals.** `traced`.
6. **Vendor variance unknown.** ColorOS may fork DocumentsUI and change the root list. `reasoned`.

**Consequence for this repo, not in the findings file: `minSdk = 24`.** The API 30 gate therefore
excludes a real slice of the supported range, so the per-file `ACTION_OPEN_DOCUMENT` fallback is
mandatory rather than defensive. Recommended shape is a `LedgerSourceCapability` gate mirroring the
old `NavCapability` pattern; both paths yield document URIs, so nothing downstream forks.

**Fallbacks stay rejected.** `drive.file` folder-grant semantics for later-added files are
undocumented by Google (both owning pages read, neither states it), and `drive.readonly` is a
restricted scope that worsens the clone-and-run blocker.

### Caveats on this resolution

- The research agent **failed with an API error** partway through a second verification pass on the
  `SDK_INT >= 30` gate. The findings file was already written and is structurally complete, and the
  gate is supported by a disassembly listing in section 1, but that re-verification never finished.
- **Nothing here was run on a device** (`adb devices` was empty). The findings file specifies a
  15-minute probe that settles sub-question 2 definitively. That probe is now its own ticket.
