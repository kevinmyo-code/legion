package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Where a scanned or picked file landed. Per
 * `.scratch/ledger-drive-ingestion/issues/03-ingested-file-ledger.md`:
 *
 * ```
 * NEW --parse+gate--> INGESTED           (rows committed, stamped with sourceFileId)
 *                 \-> QUARANTINED        (gate failed, NOTHING written, reason stored)
 *                 \-> UNREADABLE         (not a PDF, virtual doc, IO failure)
 *                 \-> DUPLICATE_CONTENT  (sha256 already known, stopped before parsing)
 *                 \-> NEEDS_LLM          (spend gate declined - "not now", never "never")
 *
 * scan: any existing record -> skip, zero cost  EXCEPT states NEW and NEEDS_LLM
 * QUARANTINED --explicit user retry--> NEW
 * size or mtime changed       --------> NEW   (the file was replaced in place)
 * replaced file's overlaps    --------> NEW   (amendment 2)
 * ```
 *
 * [NEW] is a **real stored state, not the absence of a record** (amendment 5).
 * Deleting the row to mean "new" would throw away [IngestedFile.duplicatesSkipped]
 * and the measured [IngestedFile.llmPromptTokens]/[IngestedFile.llmResponseTokens] -
 * audit history tickets 04 and 06 added deliberately - and would contradict
 * ticket 03's "records are NEVER pruned".
 *
 * [NEW] and [NEEDS_LLM] are the two states exempt from the skip rule; both mean
 * "re-examine me". [NEEDS_LLM] exists so ticket 06's spend gate re-offers a
 * declined file at every scan until approved, never permanently skipping it.
 */
enum class IngestState { NEW, INGESTED, QUARANTINED, UNREADABLE, DUPLICATE_CONTENT, NEEDS_LLM }

/**
 * Work-avoidance record for the ledger folder scan, not a correctness record -
 * double-counting protection stays where it already lives, the transaction-level
 * dedup check in [LedgerTransactionDao]. A wrong key here costs wasted work or a
 * manual re-import; it can never produce a wrong balance. See ticket 03's
 * resolution for the full reasoning.
 *
 * [driveFileId] is the **Drive file id with the `acc=N;` prefix already
 * stripped**. The probe found that prefix is a positional local-account index,
 * not part of the file's identity, so it would make the key unstable across a
 * second signed-in account for a reason unrelated to the file itself. Stripping
 * happens at the call site (the scan/import code), not here - this entity just
 * stores the already-clean id.
 *
 * This entity deliberately has **no `syncId` column** (amendment 4/ticket 10).
 * It syncs, when ticket 10's sync registration lands, on a natural primary key
 * (`driveFileId` itself) rather than a generated one, because the stripped
 * Drive file id is already a genuine cross-device identity. Do not add a
 * `syncId` column "for consistency" with the other synced entities - that
 * would be wrong for this table specifically.
 *
 * The record is **never pruned**, even if the underlying Drive file disappears
 * from a scan - a stale/empty folder listing is indistinguishable from a real
 * deletion (the probe saw `extras` come back `Bundle[EMPTY_PARCEL]` with no
 * `loading` signal), so absence from a scan must never trigger anything
 * destructive. It is the provenance of committed financial rows.
 */
@Entity(
    tableName = "ingested_files",
    indices = [Index(value = ["contentSha256"])],
)
data class IngestedFile(
    /** Drive file id, `acc=N;` prefix already stripped by the caller. */
    @PrimaryKey val driveFileId: String,
    /**
     * Which connected folder found this file. Null means the file arrived via
     * a single-file `ACTION_OPEN_DOCUMENT` pick rather than a folder scan
     * (amendment 1) - `minSdk = 24` makes the per-file fallback mandatory, and
     * a hand-picked file still gets a record so a later folder scan can
     * recognise its content hash and skip re-ingesting it.
     */
    val treeUri: String?,
    /** For the UI only, never used for identity. */
    val displayName: String,
    /** Change signal: a known file whose size or mtime changed re-enters NEW. */
    val sizeBytes: Long,
    /** Change signal, epoch millis. Confirmed a real per-file upload time, not a folder-wide stamp. */
    val lastModified: Long,
    /**
     * LEGION-computed SHA-256 over the file's bytes. Null until the bytes are
     * actually read (SAF/Drive expose no reachable content hash). Not the skip
     * key - [driveFileId]/[sizeBytes]/[lastModified] are - this recognises the
     * same content re-uploaded under a different name. Indexed: the scan's
     * hash-before-parse ordering makes this a hot lookup.
     */
    val contentSha256: String?,
    val state: IngestState,
    /** Set only when [state] is [IngestState.DUPLICATE_CONTENT]: the file this one's content matches. */
    val duplicateOfFileId: String? = null,
    /** The reconciliation gate's own failure message, set only when [state] is [IngestState.QUARANTINED]. */
    val quarantineReason: String? = null,
    /** Rows committed by this file. 0 unless [state] is [IngestState.INGESTED]. */
    val transactionCount: Int = 0,
    /** Epoch millis, first time this file was seen by a scan or pick. */
    val firstSeenAt: Long,
    /** Epoch millis, most recent scan/pick/retry attempt. Doubles as the LWW sync clock once ticket 10 registers this table. */
    val lastAttemptAt: Long,
    /**
     * Amendment 2 (ticket 04): only known once the file has actually been
     * parsed, so it starts null and is filled in on the transition to a
     * terminal state that got far enough to parse.
     */
    val accountId: String? = null,
    /** Amendment 2: earliest transaction date this file produced, for the replace flow's overlap check. */
    val minTxnDate: Long? = null,
    /** Amendment 2: latest transaction date this file produced, for the replace flow's overlap check. */
    val maxTxnDate: Long? = null,
    /**
     * Amendment 2: incoming lines that matched an existing transaction and
     * were dropped rather than double-counted. NOT NULL - 0 unless a dedup
     * check actually ran and found overlap. Makes ticket 04's "errs toward
     * dropping" behaviour auditable per file instead of invisible.
     */
    val duplicatesSkipped: Int = 0,
    /** Amendment 3 (ticket 06): whether an LLM call was actually made for this file. */
    val llmAttempted: Boolean = false,
    /** Amendment 3: measured prompt tokens from Gemini's `usageMetadata`, null until an LLM call runs. */
    val llmPromptTokens: Int? = null,
    /** Amendment 3: measured response tokens from Gemini's `usageMetadata`, null until an LLM call runs. */
    val llmResponseTokens: Int? = null,
)
