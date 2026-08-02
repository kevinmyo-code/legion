# How does a folder of statements actually get ingested?

Type: grilling
Status: open - UNBLOCKED 2026-08-02, takeable now
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
