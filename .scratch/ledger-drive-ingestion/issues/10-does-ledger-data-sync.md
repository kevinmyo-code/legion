# Does ledger data sync across devices, and does the file ledger sync with it?

Type: grilling
Status: resolved
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

---

## Resolution (2026-08-02, Kevin, 3 calls, after reading `sync/`)

### 0. FIRST: the premise this ticket inherited is WRONG

The ticket required reading `SyncEngine`/`SyncMerge` rather than deciding from doc comments. That
was the right instruction, because the code contradicts `memory/MEMORY.md`'s blocker on three
counts. All **`traced`**, read from source:

| MEMORY.md claimed | The code actually does |
|---|---|
| "Drive has no compare-and-swap" | `DriveConflict` + `DriveClient.upsert` implement version-checked optimistic concurrency: capture version, `412 PRECONDITION_FAILED`, and a **version re-check as the real guard** (Drive v3's `If-Match` is undocumented, per `DriveClient`'s own class doc), with retry up to `MAX_CONFLICT_RETRIES` |
| "shared-file last-write-wins will silently lose rows" | `syncFile` **re-merges remote into local, re-reads the merged local, then uploads that**. It is a read-merge-rewrite loop, not a blind overwrite |
| "sync must become append-only" | `SyncMerge.Mode.UNION` **is** append-only and is **already in use by 8 tables** (`memories`, `service_records`, `build_entries`, `code_events`, `oil_analyses`, `obd_samples`, `music_plays`, `monthly_recaps`, `yearly_wrapped`) |

Other facts established:

- One gzipped-NDJSON file per table (`<table>.json.gz`) in `appDataFolder`; the two high-volume
  tables shard by month.
- Registering a table is **one `Spec(...)` line** in `SyncEngine`'s registry.
- `ledger_transactions`, `pantry_receipts`, `pantry_line_items` and `ingested_files` appear
  **zero times** in `SyncEngine`. Nothing ledger-side or pantry-side syncs today.

**So sub-questions 3 and 4 dissolve.** This effort does not have to solve append-only, because
append-only exists. It does not have to avoid syncing ledger data either.

### 1. `ledger_transactions` SYNCS. `Mode.UNION`, keyed on `syncId`.

```kotlin
Spec("ledger_transactions", listOf("syncId"), Mode.UNION, naturalPk = false, hasSyncId = true)
```

Exactly the shape `service_records` and `memories` already use. Transactions are **immutable once
committed**, which is precisely what UNION assumes, so a row inserted on either device propagates
and nothing is ever overwritten. The `syncId` that `LedgerTransaction` already carries - the one
whose doc comment pointed at a sync feature nobody wired - is the identity.

LWW was rejected: wrong semantics for immutable rows, and it would reintroduce the overwrite risk
UNION exists to avoid.

### 2. `ingested_files` SYNCS. `Mode.LWW`, natural key `driveFileId`. **No `syncId` column.**

```kotlin
Spec("ingested_files", listOf("driveFileId"), Mode.LWW, naturalPk = true, clock = "lastAttemptAt")
```

**This closes ticket 03's deferred `syncId` question by REMOVING it, not by answering it.** No
column is added. It works because ticket 03 already strips the positional `acc=N;` prefix, so the
stored value is the Drive file id proper and is **identical on both devices for the same file** -
a genuine natural key. That property was established for a completely different reason (the prefix
is positional and could shift under a second account) and turns out to make cross-device identity
free.

`Mode.LWW` rather than UNION because the record is a **state machine** that legitimately changes:
`NEW -> INGESTED`, retry after `QUARANTINED`, reset to `NEW` on replacement, `NEEDS_LLM` until
approved. UNION would pin it to whichever state propagated first, so a retried or replaced file
would never update across devices. Clock is `lastAttemptAt`, which ticket 03 already defined.

**Consequences, both good:**
- Device B skips **fetch and hash entirely** for a known file, not merely the parse. The cost of a
  second device drops from bandwidth-and-time to nothing.
- `ledger_transactions.sourceFileId` **no longer dangles**. Both sides of the reference sync, so the
  third option 03 left open (sync it, null it, or tolerate a dangling reference) is answered by the
  first.

### 3. The blocker is NARROWED, not deleted. One hardening is in scope.

`memory/MEMORY.md`'s append-only entry is rewritten to what is true. What actually remains:

- **After `MAX_CONFLICT_RETRIES` the loop calls `check(...)`, which THROWS.** For a sustained
  conflict on the ledger file that is an `IllegalStateException` inside a sync pass, and nothing in
  the app reports it - Firebase is not wired and `MidnightEvents` only does `Log.d`.
- A bounded window remains between the merge and the upload. Bounded by the version re-check and
  retry, not unbounded silent loss.

**In scope for this effort:** make retry exhaustion **log and skip this pass** instead of throwing.
Ledger is now the worst thing in the app to lose, and a skipped pass retries on the next sync.

**Out of scope, explicitly:** everything else in `sync/`. Pantry tables stay unregistered - that is
a separate call, not this map's.

### Still true and unchanged

**None of `sync/` has ever run in this app.** It compiles and is ported; it has not been exercised.
Every claim above is `traced` from source, not `tested`. Registering two tables does not change
that, and the first real two-device run is where it gets found out.

### What this ticket does NOT settle

- Whether pantry syncs.
- Any on-device verification of the sync subsystem.
- The Drive OAuth signing-cert blocker, which is separate and still open.
