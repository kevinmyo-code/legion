# Does ledger data sync across devices, and does the file ledger sync with it?

Type: grilling
Status: open - UNBLOCKED 2026-08-02, takeable now
Blocked by: 03 (resolved)

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

## What 03 handed this ticket, and what it deliberately left (2026-08-02)

**03 resolved without ruling on sync, which inverts this ticket's stated premise.** The premise
above says sync "must be settled before the ingested-file ledger's schema is final". It was not.
`ingested_files` is specified and final **in every respect except sync**: it carries no `syncId`.
That was a deliberate deferral, not an oversight - pre-empting this ticket's ruling was judged worse
than adding one column later, and the additive-migration discipline makes that a one-line
`ALTER TABLE` on a v4 -> v5. **So this ticket is genuinely free to rule either way**; it is not
being handed a fait accompli.

Facts from 03 that change the shape of sub-question 2:

- **Device B re-processing everything is now much cheaper than the premise assumed.** The pipeline
  hashes bytes and stops before parsing when the content is already known
  (`DUPLICATE_CONTENT`). If transactions sync but the file ledger does not, device B still
  downloads and hashes every file, but it does **not** pay for a parse or a Gemini call on any file
  whose content device A already committed. The cost of not syncing the file ledger is therefore
  bandwidth and time, not LLM spend.
- **That saving depends entirely on transactions syncing.** The hash check matches against
  `INGESTED` records in `ingested_files`, which is device-local. If neither table syncs, device B
  pays full price for everything. The two decisions are coupled in one direction only.
- **`ledger_transactions` now has a nullable `sourceFileId`** pointing at a device-local
  `driveFileId`. If transactions sync and the file ledger does not, that column arrives on device B
  as a dangling reference. Decide whether it syncs, is nulled on transfer, or is tolerated as
  dangling.
- **`driveFileId` is account-scoped, not device-scoped.** The `acc=N;` positional prefix is stripped
  (03), so the stored id is the Drive file id proper, which is the same value on both devices for
  the same file. It is therefore a viable cross-device key, which the premise did not assume.

Sub-questions 1, 3 and 4 are untouched by 03. The append-only blocker in `memory/MEMORY.md` is
still open and still unsolved.
