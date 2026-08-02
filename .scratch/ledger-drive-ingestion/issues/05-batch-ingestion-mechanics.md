# How does a folder of statements actually get ingested?

Type: grilling
Status: resolved
Blocked by: 01 (resolved), 03 (resolved)

## Question

Single-file import is a `LaunchedEffect` in an Activity that blocks until done. A folder of sixty
PDFs, some needing a network LLM call, is a different problem.

Decide the execution model:

1. **Where it runs.** Foreground service, `WorkManager`, or a coroutine scoped to a ViewModel? PDF
   parsing is CPU-bound, LLM fallback is network-bound and slow, and the user will rotate the
   screen or leave the app mid-run.
2. **Interruption and resume.** What happens on process death halfway through forty files? The
   ingested-file ledger makes resume cheap in principle; specify the actual behavior.
3. **Atomicity boundary.** The reconciliation gate is atomic per statement (CLAUDE.md §4). Confirm
   a batch is NOT atomic as a whole: thirty-nine good statements must commit even if one
   quarantines. Say so explicitly so an implementer does not wrap the batch in a transaction.
4. **Concurrency.** Serial, or N files in parallel? Parallel is faster but multiplies peak memory
   (PdfBox holds pages in memory) and makes progress reporting and cost gating harder.
5. **Rescan trigger.** The request says new statements arrive in the folder later. Is that a manual
   Rescan button, a check on app open, or a background poll? A background poll needs a
   justification against CLAUDE.md §7's no-compulsion-mechanics rule and battery cost. Recommend
   the cheapest thing that meets the actual need.
6. **Progress reporting.** What the UI observes: a StateFlow of per-file status, a count, or both.
   This is the contract the ledger UI ticket builds against, so specify the type.

Consider whether the existing single-file `LedgerController.importStatement` becomes a special case
of the batch path or stays a separate entry point.

## What 01 and 03 handed this ticket (2026-08-02)

Read `03-ingested-file-ledger.md`'s Resolution section and
`../research/01-saf-drive-folder-findings.md`'s `## Device probe` before deciding anything here.
The parts that constrain this ticket directly:

- **Resume is already cheap and its unit is defined.** A known, unchanged file costs zero bytes, so
  process death halfway through forty files is recovered by re-running the scan. Sub-question 2 is
  now about reporting and user-visible behaviour, not about correctness.
- **Per-file cost, measured not guessed.** 637ms for a cached file, **1248ms for a freshly uploaded
  one**. Sixty uncached files is roughly a minute of pure I/O before any parse or LLM call. That is
  the number sub-questions 1 and 4 have to be argued against.
- **Peak memory has a hard floor.** The pipeline reads whole files into memory to hash them
  (`contentSha256`) before PdfBox ever opens them, so parallelism multiplies whole-PDF byte arrays,
  not just PdfBox page objects.
- **The scan must tolerate a stale listing.** The provider returns stale-empty results with **no
  signal at all** (`extras` was `Bundle[EMPTY_PARCEL]` on every query; the `loading` key never
  appeared). A scan that finds `COUNT=0`, or that finds nothing new, is a normal outcome and must
  not be reported as an error or as "the folder is empty".
- **Sync latency is real and bounds sub-question 5's rescan trigger.** A file uploaded from a
  browser was invisible to `listFiles()` for at least 2m36s, and appeared only after the Drive app
  was opened on the phone (the two variables were not isolated). Any "check on app open" or poll
  design has to accept that a just-uploaded statement will frequently not be there yet.
- **Ingestion must filter on mime type.** `flags=455` means PDFs are non-virtual and
  `openInputStream` is correct for them, but a Google-native Doc or Sheet in the same folder would
  be virtual and would fail that call (`reasoned`, not tested). Non-`application/pdf` children go to
  `UNREADABLE`, they do not crash the batch.
- **Atomicity.** 03 confirms the per-statement boundary: `INGESTED` commits its rows,
  `QUARANTINED` writes nothing. Sub-question 3's "a batch is NOT atomic as a whole" is consistent
  with that and still needs stating explicitly for implementers.

---

## Resolution (2026-08-02, Kevin, 6 calls)

### 1. Where it runs

**Inside the existing `AriaForegroundService`**, as a new service-scoped `IngestScanner`.

`AriaForegroundService` already declares `foregroundServiceType="connectedDevice|dataSync|microphone"`
and the app already holds `FOREGROUND_SERVICE_DATA_SYNC`, so this needs **no new dependency and no
manifest change**. `androidx.work` is deliberately not added: ticket 03 made process-death
durability cheap by other means, and with the rescan trigger below nothing needs to run while the
app is closed. A user-visible notification is the honest representation of a minutes-long operation
the user just asked for.

**Known watch item:** PDF parsing is CPU-bound and shares a process with a live Gemini session. A
dedicated service would not have isolated this - same process either way - so serial phase 2 (below)
is the actual mitigation.

### 2. Concurrency: two phases, split by what each is bound on

```
phase 1  fetch + sha256 + classify        PARALLEL, limit 4
           known id, size and mtime match  -> skip, zero bytes
           sha256 hits an INGESTED record  -> DUPLICATE_CONTENT, stop before parsing
           otherwise                       -> spill to cache, queue for phase 2

         >>> exact count of new files is known HERE <<<
         >>> this is where ticket 06's gate asks <<<

phase 2  parse + reconciliation gate + commit    STRICTLY SERIAL
           INGESTED     -> rows committed, each stamped with sourceFileId
           QUARANTINED  -> nothing written, reason stored
```

Parallel on phase 1 because that is where the measured per-file cost actually goes (637ms cached,
1248ms uncached), taking a 60-file first sync from roughly 72s to under 20s. Strictly serial on
phase 2 because it bounds peak PdfBox memory to one document, makes the spend gate's count exact
rather than racing work already in flight, keeps progress a simple ordered sequence, and avoids
firing concurrent Gemini calls at a possibly rate-limited key.

Statement PDFs measured 164-267 KB on the probe, so whole-file byte arrays are not the memory risk;
PdfBox's per-document objects are.

### 3. Staging

**Phase 1 completes across every file before phase 2 begins**, with each new file's bytes spilled to
`cacheDir/scan-<id>/<fileId>`.

This is what lets ticket 06 gate on an **exact** count of new files before a single parse runs,
while keeping peak memory to one file and downloading each file exactly once. About 16 MB of
transient disk for 60 files at observed sizes.

**Cleanup is an explicit obligation, three parts:**
1. Each cache entry is deleted as soon as phase 2 consumes it.
2. The whole `scan-<id>/` directory is deleted in a `finally`, whatever the outcome.
3. Orphaned `scan-*` directories from a previously killed run are deleted when a scan starts.

### 4. Interruption and resume

**A killed scan is re-run, not resumed.** Known unchanged files cost zero bytes (ticket 03), so
recovery is automatic and needs no resume protocol, no checkpoint, and no partial-state schema.
Nothing partial is ever committed because the gate is per statement.

### 5. Atomicity, stated explicitly

The reconciliation gate is atomic **per statement**. **A batch is NOT atomic as a whole.** Thirty-
nine good statements commit even if the fortieth quarantines. **Do not wrap the batch in a
transaction.** The only multi-row transaction in this design is the replace flow from ticket 03
(delete a file's old rows, insert its new ones).

### 6. Rescan trigger

**One child-documents query per connected folder on app open, diffed against `ingested_files`.**
Zero bytes, zero parsing, zero spend. Unknown ids surface as a quiet inline count ("3 new
statements") that the user taps to start an actual scan. **Never auto-ingest.**

Against CLAUDE.md §7: the surfacing is passive and in-app, not a notification engineered to pull
the user back, so it is not a re-engagement mechanic. A background periodic poll was rejected - it
would need the WorkManager dependency §1 avoided, costs battery for a monthly-cadence event, and
its natural UI is exactly the notification §7 prohibits.

It also degrades honestly against the measured sync latency: a just-uploaded statement is often not
in the listing yet, and the count then says nothing rather than claiming the folder is empty.

### 7. Progress contract (this is what ticket 08 builds against)

A single `StateFlow<ScanState>` exposed by `IngestScanner`:

```kotlin
sealed interface ScanState {
    data object Idle : ScanState
    data class Listing(val folderCount: Int) : ScanState
    data class Staging(val done: Int, val total: Int) : ScanState
    data class AwaitingApproval(val newFiles: Int, val estimate: SpendEstimate) : ScanState
    data class Parsing(val done: Int, val total: Int, val currentName: String) : ScanState
    data class Finished(val results: FileResults) : ScanState
}
```

`FileResults` carries the accumulated per-file outcomes (ingested / quarantined / unreadable /
duplicate / skipped), so the future quarantine review UX can observe the scan that produced an
outcome rather than re-querying the database for it.

One `StateFlow` rather than a phase flow plus an event flow: a collector attaching late (which is
exactly what rotation does) gets current state immediately with nothing lost. Genuinely one-shot
things - a snackbar for an unexpected failure - go on a **separate `Channel<ScanEvent>`**, per the
repo's vendored `kotlin-flow-state-event-modeling` guidance. Do not smuggle events into state.

`SpendEstimate`'s shape, and whether `AwaitingApproval` blocks or merely informs, are **ticket 06's**
call. This ticket only fixes where in the pipeline it sits.

### 8. Single-file import is unified

`LedgerController.importStatement` becomes a **one-element run through the same pipeline**, so a
hand-picked file gets a file record, a content hash and a `sourceFileId` exactly like a scanned one.
Concrete payoff: import a statement by hand, and when the same file later appears in a connected
folder, the hash check recognises it and skips it instead of re-parsing and re-paying.

**This amends ticket 03:** `ingested_files.treeUri` becomes **nullable**, null meaning the file came
from a single-file `ACTION_OPEN_DOCUMENT` pick. Signed off by Kevin in the same session, recorded in
03 as an amendment rather than a silent edit.

Two ingestion paths were rejected: both would have to honour the reconciliation gate independently
and stay correct as parsers change, and the existing single-file path already has untested DB-write
behaviour (CLAUDE.md §10).

### 9. Filtering and non-errors

- Children whose mime type is not `application/pdf` are recorded `UNREADABLE`. They never crash or
  abort the batch. This is what catches a Google-native Doc or Sheet, which would be a virtual
  document and would fail `openInputStream` (`reasoned`, not tested).
- A listing that returns nothing, or returns nothing new, is a **normal outcome**. It is never
  surfaced as an error and never as "the folder is empty", because the provider serves stale-empty
  results with no signal at all.

### What this ticket does NOT settle

- The spend estimate's content, and whether approval blocks or informs. **Ticket 06.**
- The screens that render `ScanState`. **Ticket 08.**
- `accountId` derivation for a mixed-institution folder. Still open on the map.

---

## Amendment 1 (2026-08-02, from ticket 06, Kevin signed off in session)

**`AwaitingApproval` moves.** This ticket placed it immediately after staging (phase 1). Ticket 06
moved it to **between phase 2a and 2b**.

**Why.** After staging, only the *new file* count is known. The **LLM** count is not - a file only
reveals it needs the LLM by failing both deterministic parsers. So a gate placed after staging can
only quote a worst case ("up to 60 files may need AI reading") when the truth is usually zero.
Inflated warnings train click-through, which is the exact failure mode ticket 06 exists to prevent.

**Phase 2 splits in two:**

```
phase 2a  deterministic parse, ALL staged files   serial
            Success     -> COMMIT NOW
            Quarantined -> record, no LLM call
            NeedsLlm    -> set aside
          >>> gate asks HERE. count EXACT. spend so far: zero <<<
phase 2b  LLM for the approved set                serial
```

Deterministic parsing never calls Gemini, so the exact count is free. `StatementDispatcher` splits
into `dispatchDeterministic` and `runLlm`.

**`ScanState` changes accordingly:** `Staging` is followed by a deterministic-parse state, then
`AwaitingApproval`, then the LLM phase. Both parse phases report progress separately - they have
different costs and the user should see which one is running.

Everything else in this ticket stands: phase 1 parallelism, cacheDir staging and its three-part
cleanup, re-run-not-resume, batch-not-atomic, listing-only rescan trigger, single StateFlow.
