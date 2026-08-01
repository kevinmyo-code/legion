# Does ledger data sync across devices, and does the file ledger sync with it?

Type: grilling
Status: open
Blocked by: 03

## Question

`LedgerTransaction` carries a `syncId` with a comment pointing at the sync feature, so someone
intended it to sync, but nothing registers it with `SyncEngine`. This must be settled before the
ingested-file ledger's schema is final, because it changes the identity requirements.

1. **Should it sync at all?** One shared Google account across two phones is the model
   (CLAUDE.md §2). If ledger syncs, ingesting on the phone means the tablet knows. If it does not,
   two devices pointed at the same Drive folder both ingest everything and double-count.
2. **Does the ingested-file record sync?** This is the sharper question. If transactions sync but
   the file ledger does not, device B re-processes every file, spends LLM money again, and relies
   on per-transaction dedup to save it. That is exactly the layer being loosened to preserve twins,
   so the two decisions interact directly.
3. **The append-only problem.** `memory/MEMORY.md` records an open blocker: Drive has no
   compare-and-swap, so last-write-wins will silently lose rows, and sync must become append-only.
   Ledger data is the worst possible thing to silently lose rows from. Decide whether this effort
   must solve append-only, or whether it must instead avoid syncing ledger data until that is
   solved elsewhere.
4. **Scope check.** Solving append-only sync properly may be a separate effort. If so, rule it out
   of scope here and record what this map does instead as an interim.

Reaching for the existing `SyncEngine`/`SyncMerge` code is required, not optional; do not decide
this from the doc comments alone.
