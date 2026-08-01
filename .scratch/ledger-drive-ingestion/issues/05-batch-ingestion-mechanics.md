# How does a folder of statements actually get ingested?

Type: grilling
Status: open
Blocked by: 01, 03

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
