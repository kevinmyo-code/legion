# What identifies a statement file as already ingested?

Type: grilling
Status: open
Blocked by: 01

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
