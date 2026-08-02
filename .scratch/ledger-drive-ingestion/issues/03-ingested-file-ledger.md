# What identifies a statement file as already ingested?

Type: grilling
Status: resolved
Blocked by: 01 (resolved)

## Question

Nothing today records that a file was ingested. `LedgerTransaction.sourceFile` holds a display name
and that is all. The request is explicit: the app must know which statements it has already
processed so a folder re-scan does not duplicate work.

Design the record. Decide:

1. **Identity.** What makes two files "the same file"? **The SAF research has now narrowed this:
   SAF exposes no content hash at all** - `DocumentsContract.Document`'s column set is closed and
   Drive's own `md5Checksum` is unreachable through a tree URI (`traced`). So a hash must be
   computed by LEGION over the bytes, which costs a full read per file. Available cheaply instead:
   the document id (form `acc=<localAccountIndex>;doc=<driveFileId>`), size, and last-modified.
   Decide the combination - the research recommends Drive file id as the key with a LEGION-computed
   SHA-256 for content identity and size/mtime as change signals, but that is a recommendation, not
   a ruling.
2. **States.** At minimum: imported, quarantined, skipped-as-duplicate. Does a quarantined file
   stay quarantined forever, or become retryable? Does the record survive the user deleting the
   underlying Drive file?
3. **Relationship to transactions.** Does a row link to the transactions it produced, so a bad
   import can be rolled back atomically? The gate is already atomic per statement, so the rollback
   unit exists conceptually; it is not represented in the schema.
4. **Schema.** New Room entity, DAO, and a real v3 to v4 migration with verbatim generated SQL per
   CLAUDE.md §5.
5. **Cost of a scan.** If identity is a content hash, a 60-file folder means downloading and
   hashing 60 PDFs on every scan. Is that acceptable, or does a cheap pre-filter run first?

The output is a schema plus a state machine, precise enough to implement without further calls.

---

## Resolution (2026-08-02, Kevin, 7 calls)

**The record's job is WORK AVOIDANCE, not correctness.** Correctness against double-counting stays
where it already lives, the transaction-level check that ticket 04 is fixing. A file key that is
wrong in either direction costs wasted work or a manual re-import; it can never produce a wrong
balance. This is the ruling that sets the rigour bar for everything below: it is what licenses a
metadata-only skip filter instead of hashing every file on every scan.

### 1. Identity

**Cheap skip filter, no byte reads:** `driveFileId`, with `sizeBytes` and `lastModified` as change
signals. A known id whose size and mtime both match is skipped outright.

**`acc=N;` is stripped before storage.** The device probe showed that prefix is a positional
account index, not an identifier, so it can shift under a second signed-in account. Storing it
would make the key unstable for a reason that has nothing to do with the file. (`reasoned` - one
account was signed in during the probe, so the shift itself was not observed.)

**`contentSha256` is recorded but is not the skip key.** It costs nothing extra because the bytes
are already in memory to be parsed. Its job is recognising the same content arriving under a
different name - the case the probe folder already contains, `Copy of eStmt_2025-12-05 (1).pdf`.

`lastModified` is safe to use as a change signal: the probe confirmed it is a real per-file upload
timestamp, not a folder-wide stamp. The first five files sharing one value was a batch-upload
artefact, disambiguated by the sixth.

### 2. States

`INGESTED` | `QUARANTINED` | `UNREADABLE` | `DUPLICATE_CONTENT` | `NEEDS_LLM` (amendment 3)

```
NEW --parse+gate--> INGESTED           (rows committed, stamped with sourceFileId)
                \-> QUARANTINED        (gate failed, NOTHING written, reason stored)
                \-> UNREADABLE         (not a PDF, virtual doc, IO failure)
                \-> DUPLICATE_CONTENT  (sha256 already known, stopped before parsing)

scan: any existing record -> skip, zero cost  EXCEPT state NEEDS_LLM (amendment 3)
QUARANTINED --explicit user retry--> NEW
size or mtime changed       --------> NEW   (the file was replaced in place)
```

**Quarantine is escapable only by explicit user action, never automatically.** Parsers improve and
the LLM is nondeterministic, so permanent quarantine is wrong; but auto-retry silently re-pays for
a Gemini call on every scan of a file that will probably fail the same way, which is precisely what
ticket 06 exists to prevent. `state` + `quarantineReason` are the hook the quarantine review UX
will hang off when it is designed.

**The record survives deletion of the underlying Drive file, and is NEVER pruned.** It is the
provenance of committed financial rows. Decisive evidence from the probe: a folder listing can come
back stale or empty with **no signal to the caller** (`extras` was `Bundle[EMPTY_PARCEL]` on every
query, the `loading` key never appeared, yet the picker served "No items" for a folder holding
five). So absence from a scan is not evidence of deletion and must never trigger anything
destructive. Pruning would convert a display concern into a data-integrity risk.

### 3. Relationship to transactions

`ledger_transactions` gains a **nullable `sourceFileId TEXT`, with no `@ForeignKey` constraint.**

Nullable because the per-file `ACTION_OPEN_DOCUMENT` fallback that `minSdk = 24` makes mandatory,
and any hand-picked single import, have no folder-scan record behind them. No FK because
`onDelete = CASCADE` would let deleting a file record silently delete committed financial rows; the
rollback wanted here is better written as an explicit delete that is visible in the code.

This column is the only mechanism that makes the map's open **"corrected or replaced statements
upstream"** item solvable at all, and it gives the ledger UI a real per-row provenance view.

### 4. Schema

New entity `ingested_files`:

| Column | Type | Notes |
|---|---|---|
| `driveFileId` | TEXT, PRIMARY KEY | `acc=N;` prefix stripped |
| `treeUri` | TEXT, nullable | which connected folder found it. **Null = single-file pick.** See amendment below |
| `displayName` | TEXT NOT NULL | for the UI only, never for identity |
| `sizeBytes` | INTEGER NOT NULL | change signal |
| `lastModified` | INTEGER NOT NULL | change signal, per-file upload time |
| `contentSha256` | TEXT, nullable | null until the bytes are read. **Indexed** |
| `state` | TEXT NOT NULL | the four above |
| `duplicateOfFileId` | TEXT, nullable | set only for `DUPLICATE_CONTENT` |
| `quarantineReason` | TEXT, nullable | the gate's own message |
| `transactionCount` | INTEGER NOT NULL | rows committed, 0 unless `INGESTED` |
| `firstSeenAt` | INTEGER NOT NULL | epoch millis |
| `lastAttemptAt` | INTEGER NOT NULL | epoch millis |
| `llmAttempted` | INTEGER NOT NULL | **amendment 3.** Did an LLM call run |
| `llmPromptTokens` | INTEGER, nullable | **amendment 3.** Measured, from `usageMetadata` |
| `llmResponseTokens` | INTEGER, nullable | **amendment 3.** Measured, from `usageMetadata` |
| `accountId` | TEXT, nullable | **amendment 2.** Only known after parsing |
| `minTxnDate` | INTEGER, nullable | **amendment 2.** Earliest txn date this file produced |
| `maxTxnDate` | INTEGER, nullable | **amendment 2.** Latest txn date this file produced |
| `duplicatesSkipped` | INTEGER NOT NULL | **amendment 2.** Lines that matched existing rows |

**Multi-folder is carried in the schema now**, hence `treeUri`. This is explicitly a two-country
SGD/USD household ledger and Kevin's Drive already separates USA and Singapore statements into
different folders, so single-folder is a constraint that would be hit immediately. The connected
folder set itself lives in **DataStore as a `Set<String>`**, not a Room table: it is a handful of
URI strings with no relations.

**Migration v3 -> v4:** `CREATE TABLE ingested_files` plus its `contentSha256` index, and
`ALTER TABLE ledger_transactions ADD COLUMN sourceFileId TEXT`. Per CLAUDE.md §5 the SQL is
**transcribed verbatim from the generated `app/schemas/com.kevin.legion.data.local.CarDatabase/4.json`**,
not hand-written from the table above - that table is a specification, not the migration source.
Migration test required.

**`syncId` is deliberately NOT on this entity.** Whether the file ledger syncs is ticket 10's call.
**Known tension, recorded rather than hidden:** ticket 10 states this must be settled before 03's
schema is final. It is not, so **`ingested_files` is final in every respect except sync**. That is
judged safe because the additive-migration discipline makes adding the column later a one-line
`ALTER TABLE`, which is strictly cheaper than pre-empting 10's ruling now.

### 5. Cost of a scan

Resolved by the identity choice: **a known, unchanged file costs zero bytes.** Only new or changed
files are downloaded.

For a file that must be fetched, the order of operations puts the cheap check first:

```
download bytes
  -> sha256
     -> matches an INGESTED record?
          yes -> record DUPLICATE_CONTENT, duplicateOf = <that id>
                 STOP. no parse, no LLM call, no rows
          no  -> parse -> reconciliation gate -> INGESTED | QUARANTINED
```

Stopping at the hash keeps a duplicate file's saved LLM call out of ticket 06's spend estimate
entirely, rather than paying for a parse that is guaranteed to produce zero net rows.

Replace flow, when a known file's size or mtime changed and it re-ingests successfully:

```
DELETE FROM ledger_transactions WHERE sourceFileId = :driveFileId
INSERT <new rows>
```

both inside one Room transaction.

Measured input for ticket 05: the probe read a cached file in **637ms** and a freshly uploaded one
in **1248ms**. Sixty uncached files is roughly a minute of pure I/O before any parsing or LLM call.

### What this ticket does NOT settle

- `accountId` derivation and mixed-institution folders. Still open on the map.
- The quarantine review UX. Still open; this supplies its state and reason fields.
- Whether `ingested_files` syncs. Ticket 10.
- How the scan executes, and its concurrency and progress contract. Ticket 05.
- The spend gate that consumes the "N files will need an LLM call" count. Ticket 06.

---

## Amendment 1 (2026-08-02, from ticket 05, Kevin signed off in session)

**`treeUri` changes from `NOT NULL` to nullable.** Null means the file arrived through a single-file
`ACTION_OPEN_DOCUMENT` pick rather than a folder scan.

**Why.** Ticket 05 unified `LedgerController.importStatement` into the batch pipeline as a
one-element run, so a hand-picked file now gets a file record, a content hash and a `sourceFileId`
exactly like a scanned one. A hand-picked file has no tree URI, so `NOT NULL` made unification
impossible.

**What it buys.** Import a statement by hand today; when that same file later turns up in a
connected folder, the hash check recognises it and records `DUPLICATE_CONTENT` instead of re-parsing
it and possibly re-paying for an LLM call. Under the original `NOT NULL` schema the hand-import path
would have written no record at all, so it would have got none of that protection and its rows would
have had no provenance.

Nothing else in this ticket's resolution changes. The skip filter, the four states, the never-prune
rule, the hash-before-parse ordering and the `sourceFileId` column are all unaffected.

---

## Amendment 2 (2026-08-02, from ticket 04, Kevin signed off in session)

**Four columns added to `ingested_files`:** `accountId` (nullable, only known after parsing),
`minTxnDate`, `maxTxnDate` (nullable, the date range of the transactions this file produced), and
`duplicatesSkipped` (NOT NULL, count of incoming lines that matched existing rows).

**Why - this closes a hole in THIS ticket's replace flow that ticket 04 exposed.** Ticket 04 keys
dedup on counting per tuple rather than boolean existence, which means an overlapping statement can
legitimately contribute **zero rows**. A transaction attested to by both a monthly and a
year-to-date statement therefore exists under only the monthly's `sourceFileId`. This ticket's
replace flow (`DELETE FROM ledger_transactions WHERE sourceFileId = :id`) would destroy those
transactions outright, and because the YTD file is already `INGESTED` a rescan skips it, so the data
never comes back. **Silent financial data loss, which is precisely what CLAUDE.md §4 exists to
prevent.**

**The replace flow is therefore amended** to reset overlapping files so the next scan restores what
they should have contributed:

```
DELETE FROM ledger_transactions WHERE sourceFileId = :fileId
UPDATE ingested_files SET state = 'NEW'
  WHERE accountId = :accountId
    AND driveFileId != :fileId
    AND state = 'INGESTED'
    AND minTxnDate <= :replacedMax AND maxTxnDate >= :replacedMin
```

`minTxnDate`/`maxTxnDate` are what bound that reset to genuinely overlapping statements instead of
re-ingesting the whole account. `duplicatesSkipped` makes ticket 04's "errs toward dropping"
behaviour auditable per file rather than invisible.

Nothing else in this ticket's resolution changes.

---

## Amendment 3 (2026-08-02, from ticket 06, Kevin signed off in session)

**Fifth state `NEEDS_LLM`, EXEMPT from the skip rule.** Plus three columns: `llmAttempted`,
`llmPromptTokens`, `llmResponseTokens`.

**Why the exemption.** This ticket's rule was "any existing record -> skip, regardless of state".
Ticket 06 gates LLM spend and lets the user decline. Collision:

- declined file WITH a record -> skipped forever, can never be approved later
- declined file with NO record -> forgotten entirely

So `NEEDS_LLM` is re-offered at every scan until approved or until the file changes. **Declining is
always "not now", never "never".**

**Why the token columns.** `SubAgent` currently discards `usageMetadata`, which Gemini returns on
every call. Ticket 06 adds parsing and records the measured counts here. Payoff: after one real
batch the cost estimate rests on measured tokens instead of a reasoned number derived from a
reasoned number.

Nothing else in this ticket's resolution changes.

---

## Amendment log

| # | From | Change |
|---|---|---|
| 1 | ticket 05 | `treeUri` -> nullable. Null = single-file pick. Unifies hand-import and scan |
| 2 | ticket 04 | +`accountId`, `minTxnDate`, `maxTxnDate`, `duplicatesSkipped`. Replace flow resets overlapping files. **Closed silent financial data loss** |
| 3 | ticket 06 | +`NEEDS_LLM` state, exempt from skip rule. +`llmAttempted`, `llmPromptTokens`, `llmResponseTokens` |
| 4 | ticket 10 | **No `syncId` column.** Syncs via `naturalPk` on `driveFileId`. Deferral closed by removal; schema now FINAL |

Four amendments in one session. **Signal that this ticket resolved early.** Nothing it decided was
overturned, but the schema was not stable until 04, 05 and 06 had run. If a future effort resolves a
schema ticket before the tickets that consume it, expect the same.

---

## Amendment 4 (2026-08-02, from ticket 10) - the deferral is CLOSED, by removal

**`ingested_files` gets NO `syncId` column.** The question this ticket deferred is answered by
deleting it rather than by adding anything.

Ticket 10 registers the table with `naturalPk = true` keyed on **`driveFileId`**:

```kotlin
Spec("ingested_files", listOf("driveFileId"), Mode.LWW, naturalPk = true, clock = "lastAttemptAt")
```

This works **because of a decision made in this ticket for an unrelated reason**: the positional
`acc=N;` prefix is stripped, so the stored value is the Drive file id proper and is identical on
both devices for the same file. That makes it a genuine cross-device natural key. `lastAttemptAt`,
also defined here, serves as the LWW clock.

`ledger_transactions` syncs too (`Mode.UNION` on `syncId`), so **`sourceFileId` no longer dangles** -
both sides of the reference are present on every device.

**The schema in this ticket is now FINAL.** All four amendments are applied; nothing further is
deferred.
