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

`INGESTED` | `QUARANTINED` | `UNREADABLE` | `DUPLICATE_CONTENT`

```
NEW --parse+gate--> INGESTED           (rows committed, stamped with sourceFileId)
                \-> QUARANTINED        (gate failed, NOTHING written, reason stored)
                \-> UNREADABLE         (not a PDF, virtual doc, IO failure)
                \-> DUPLICATE_CONTENT  (sha256 already known, stopped before parsing)

scan: any existing record -> skip, zero cost, regardless of state
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
| `treeUri` | TEXT NOT NULL | which connected folder found it |
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
