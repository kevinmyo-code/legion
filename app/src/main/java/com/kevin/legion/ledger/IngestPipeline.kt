package com.kevin.legion.ledger

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.IngestState
import com.kevin.legion.data.local.IngestedFile
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.ledger.LedgerAspectSeeder
import com.kevin.legion.engine.ledger.LedgerRecordBridge
import java.security.MessageDigest
import org.json.JSONObject

/**
 * The per-file classify/stage/commit core both
 * [com.kevin.legion.service.IngestScanner]'s folder scan and
 * [LedgerController]'s single-file pick funnel through -
 * `.scratch/ledger-drive-ingestion/issues/05-batch-ingestion-mechanics.md`
 * resolution §8: "Single-file import is unified... a one-element run through
 * the SAME PIPELINE." SAF listing, parallel fetch, the cacheDir spill/cleanup
 * obligations, and the [com.kevin.legion.service.ScanState] StateFlow/gate
 * machinery all stay in `IngestScanner`, which is scan-specific and
 * Android-service-scoped; everything here is the work a caller does ONCE it
 * already has one file's bytes and identity metadata in hand, which is why
 * it can stay a plain object with no service lifecycle of its own.
 *
 * **Cutover 3** (`docs/architecture/cutover3-2026-08-24.md`). [commit]'s `Success` branch now writes
 * every transaction row through [RecordStore] - the engine's single write door - inside ONE
 * `db.withTransaction` block, rather than [com.kevin.legion.data.local.LedgerTransactionDao.insertAll]
 * against the legacy `ledger_transactions` table. CLAUDE.md §4 rule 7's provisional-row supersession
 * moves INTO this same transaction (the legacy-table version,
 * [com.kevin.legion.data.local.LedgerTransactionDao.deleteSupersededProvisional], is dead code as of
 * this branch - nothing calls it anymore, since nothing writes the legacy table anymore): when a
 * reconciled (`DETERMINISTIC`/`LLM_RECONCILED`) file commits, every ACTIVE engine
 * `RecordProvenance.UNRECONCILED` `Transaction` record for the same physical card ([sameCard],
 * suffix-matched exactly as the retired legacy query was) whose `txnDate` falls inside the incoming
 * statement's own range is trashed via [RecordStore.delete] BEFORE the dedup read, in the same
 * transaction as the inserts that follow - the identical three load-bearing properties the legacy
 * version's own doc comment named (the guard, before-the-dedup-read ordering, inside-the-transaction
 * atomicity), just applied to [com.kevin.legion.data.local.EngineRecord] rows instead of
 * `LedgerTransaction` rows. `ingested_files` stays plugin-internal and untouched by this cutover -
 * its own writes inside this same transaction are exactly what they were before.
 *
 * **Any [RecordStore.WriteResult.Failure] anywhere in the transaction - a superseded-row delete, a
 * replace-flow delete, or a fresh row create - throws [EngineWriteFailedException], which
 * `db.withTransaction` catches and rolls back in full** (CLAUDE.md §4 rule 2's "nothing partial is
 * ever written", now also covering a POST-gate engine-write failure that plain `Insert` calls could
 * never produce - the same class of failure [com.kevin.legion.pantry.PantryController.writeReceipt]
 * already guards against, applied here to a possibly-many-row statement instead of one receipt). The
 * caller is told in words that nothing was saved (CLAUDE.md §7) via [CommitOutcome.EngineWriteFailed],
 * and the file's [IngestedFile] record is written back to [IngestState.NEW] OUTSIDE the rolled-back
 * transaction, so the file is re-offered on the next scan rather than silently vanishing.
 */
object IngestPipeline {

    /** Thrown only to force [androidx.room.withTransaction] to roll back the whole file commit -
     * Room's transaction helper rolls back on a thrown exception, never a plain early return, so a
     * partial engine write (some rows landed, a later one did not, or a supersede/replace delete
     * failed) needs a real throw to undo. Caught immediately inside [commit] and never escapes it -
     * same shape as [com.kevin.legion.pantry.PantryController]'s own `EngineWriteFailedException`. */
    private class EngineWriteFailedException(val reason: String) : Exception()

    /**
     * [driveFileId] with the `acc=N;` prefix stripped, per ticket 03
     * resolution §1: that prefix is a positional local-account index, not
     * part of the file's identity, and would make the key unstable across a
     * second signed-in account for a reason unrelated to the file itself.
     */
    fun stripAccountPrefix(documentId: String): String =
        documentId.replaceFirst(Regex("^acc=[^;]*;"), "")

    /**
     * Inverse of [stripAccountPrefix] - reattaches the `acc=N;` prefix a stored
     * [com.kevin.legion.data.local.IngestedFile.driveFileId] had stripped off, so the id can be
     * turned back into a document URI [android.provider.DocumentsContract] will actually resolve.
     *
     * **The reason this function has to exist at all**: `driveFileId` is a KEY, not an ADDRESS.
     * [stripAccountPrefix] drops the prefix so the same physical Drive file hashes to the same key
     * across a second signed-in local account (ticket 03's rationale, unchanged, still correct) -
     * but Drive's own SAF provider only ever recognizes a document id WITH that prefix attached.
     * A live folder scan never notices this split because [com.kevin.legion.service.IngestScanner]
     * opens bytes with the child's original, unstripped `documentId` and only strips the copy it
     * writes to the database. [ReingestDryRun] rebuilds a document URI FROM the stored (stripped)
     * key, so it has to put the prefix back on before calling
     * [android.provider.DocumentsContract.buildDocumentUriUsingTree], or every file reads as
     * UNREACHABLE against a real, valid SAF grant - confirmed on-device 2026-08-27, 107/107 files.
     *
     * [treeDocumentId] is [android.provider.DocumentsContract.getTreeDocumentId] of the SAME tree
     * URI the file was scanned under - the prefix is derived from the live grant, never hardcoded,
     * because a non-Drive SAF provider (local storage, another cloud app) prints no `acc=` prefix
     * at all and there is nothing correct to invent for it. Two guards keep this idempotent rather
     * than merely "usually right":
     * - if [treeDocumentId] itself carries no `acc=` prefix, [documentId] is returned unchanged -
     *   there is nothing to reattach, and fabricating one would address a file that does not exist;
     * - if [documentId] already carries a prefix (an already-full id passed in by mistake, or a
     *   future caller that stopped stripping), it is returned unchanged rather than double-prefixed.
     */
    fun reattachAccountPrefix(documentId: String, treeDocumentId: String): String {
        val prefixMatch = Regex("^acc=[^;]*;").find(treeDocumentId) ?: return documentId
        if (documentId.startsWith("acc=")) return documentId
        return prefixMatch.value + documentId
    }

    /**
     * Whether [displayName]/[mimeType] is a file this pipeline will even
     * attempt to read - the gate before a single byte is fetched. **Not
     * "accept everything"**: an unrecognized file must still land
     * [IngestState.UNREADABLE], per the READ-FIRST brief for this ticket.
     *
     * PDF stays gated on mime type alone (`application/pdf`), unchanged from
     * before - SAF providers report it consistently and this has been the
     * working rule since ticket 03.
     *
     * CSV is gated on EXTENSION, not mime type, `reasoned` rather than
     * observed against every SAF provider: providers are inconsistent about
     * what mime type a `.csv` gets served as (`text/csv`,
     * `text/comma-separated-values`, `application/vnd.ms-excel`, sometimes
     * the generic `application/octet-stream` when the provider has no better
     * guess) - the account-mapping brief names this inconsistency
     * explicitly. The extension is the one signal Kevin's own upload
     * actually controls. `application/octet-stream` alone, with no `.csv`
     * extension, is NOT accepted on its own - that mime type is the generic
     * fallback for countless non-statement binaries, and accepting it
     * unconditionally would defeat "not everything".
     */
    fun isAcceptableStatementFile(displayName: String, mimeType: String): Boolean {
        if (mimeType == "application/pdf") return true
        val ext = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return ext == "csv"
    }

    /** SHA-256 over [bytes], hex-encoded. Computed once bytes are already in memory to be parsed - costs nothing extra. */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Phase 1's outcome for one file - see ticket 05 resolution §2 and ticket 03's state diagram. */
    sealed class StageOutcome {
        /** Known, unchanged, and not [IngestState.NEW]/[IngestState.NEEDS_LLM] - zero bytes read. */
        data object Skipped : StageOutcome()
        /** Content hash matched an already-[IngestState.INGESTED] file under a different id. */
        data class DuplicateContent(val duplicateOfFileId: String) : StageOutcome()
        /** Not `application/pdf`, or the bytes could not be read at all (IO failure). */
        data class Unreadable(val reason: String) : StageOutcome()
        /**
         * Ready for phase 2. [isReplace] plus the previous window is what the
         * replace flow (ticket 03 amendment 2, via ticket 04) needs at commit
         * time: this file was previously [IngestState.INGESTED] under
         * different bytes (size or mtime changed - "the file was replaced in
         * place"), so a successful re-parse must delete its old rows and
         * reset any overlapping file back to [IngestState.NEW] rather than
         * just inserting alongside the stale ones.
         */
        data class Staged(
            val bytes: ByteArray,
            val isReplace: Boolean,
            val previousAccountId: String?,
            val previousMinTxnDate: Long?,
            val previousMaxTxnDate: Long?,
        ) : StageOutcome()
    }

    /**
     * Phase 1 for one file: decides skip / duplicate / unreadable / staged
     * against [com.kevin.legion.data.local.IngestedFileDao], hashing only
     * when a byte read actually happens. [readBytes] is supplied by the
     * caller (SAF `openInputStream` for the scanner; the content resolver
     * read the single-pick path already did) so this stays testable without
     * hard-coding an Android I/O mechanism.
     */
    suspend fun stage(
        context: Context,
        driveFileId: String,
        treeUri: String?,
        displayName: String,
        sizeBytes: Long,
        lastModified: Long,
        mimeType: String,
        readBytes: suspend () -> ByteArray?,
    ): StageOutcome {
        val dao = CarDatabase.getDatabase(context).ingestedFileDao()
        val now = System.currentTimeMillis()
        val existing = dao.getByDriveFileId(driveFileId)

        if (!isAcceptableStatementFile(displayName, mimeType)) {
            val reason = "Not a supported statement file ($mimeType)"
            dao.upsert(
                blank(existing, driveFileId, treeUri, displayName, now).copy(
                    displayName = displayName, sizeBytes = sizeBytes, lastModified = lastModified,
                    state = IngestState.UNREADABLE, quarantineReason = reason, lastAttemptAt = now,
                )
            )
            return StageOutcome.Unreadable(reason)
        }

        // Cheap skip filter, no byte reads: a known id whose size and mtime
        // both still match is done, UNLESS it's one of the two states that
        // mean "re-examine me" (ticket 03 amendment 5 / ticket 06 amendment 3).
        val unchanged = existing != null &&
            existing.state != IngestState.NEW && existing.state != IngestState.NEEDS_LLM &&
            existing.sizeBytes == sizeBytes && existing.lastModified == lastModified
        if (unchanged) return StageOutcome.Skipped

        val bytes = readBytes() ?: run {
            val reason = "Couldn't read this file's bytes."
            dao.upsert(
                blank(existing, driveFileId, treeUri, displayName, now).copy(
                    displayName = displayName, sizeBytes = sizeBytes, lastModified = lastModified,
                    state = IngestState.UNREADABLE, quarantineReason = reason, lastAttemptAt = now,
                )
            )
            return StageOutcome.Unreadable(reason)
        }

        val hash = sha256(bytes)
        val duplicate = dao.findIngestedByContentHash(hash)?.takeIf { it.driveFileId != driveFileId }
        if (duplicate != null) {
            dao.upsert(
                blank(existing, driveFileId, treeUri, displayName, now).copy(
                    displayName = displayName, sizeBytes = sizeBytes, lastModified = lastModified,
                    contentSha256 = hash, state = IngestState.DUPLICATE_CONTENT,
                    duplicateOfFileId = duplicate.driveFileId, lastAttemptAt = now,
                )
            )
            return StageOutcome.DuplicateContent(duplicate.driveFileId)
        }

        val isReplace = existing != null && existing.state == IngestState.INGESTED &&
            (existing.sizeBytes != sizeBytes || existing.lastModified != lastModified)

        dao.upsert(
            blank(existing, driveFileId, treeUri, displayName, now).copy(
                displayName = displayName, sizeBytes = sizeBytes, lastModified = lastModified,
                contentSha256 = hash, state = IngestState.NEW, quarantineReason = null,
                duplicateOfFileId = null, lastAttemptAt = now,
            )
        )
        return StageOutcome.Staged(
            bytes = bytes,
            isReplace = isReplace,
            previousAccountId = existing?.accountId,
            previousMinTxnDate = existing?.minTxnDate,
            previousMaxTxnDate = existing?.maxTxnDate,
        )
    }

    private fun blank(
        existing: IngestedFile?,
        driveFileId: String,
        treeUri: String?,
        displayName: String,
        now: Long,
    ): IngestedFile = existing ?: IngestedFile(
        driveFileId = driveFileId, treeUri = treeUri, displayName = displayName, sizeBytes = 0,
        lastModified = 0, contentSha256 = null, state = IngestState.NEW,
        firstSeenAt = now, lastAttemptAt = now,
    )

    /** Outcome of committing a parsed file - phase 2a (Success/Quarantined) or phase 2b. */
    sealed class CommitOutcome {
        /**
         * [restatementsSkipped] is the subset of [duplicatesSkipped] dropped
         * by the overlapping-statement pass rather than by an exact match -
         * see [LedgerDedupResolution.restatementsSkipped]. Carried out to the
         * import message so a driver whose two statements overlap can see WHY
         * a file that looked like it held 40 transactions contributed 37.
         */
        data class Ingested(
            val transactionCount: Int,
            val duplicatesSkipped: Int,
            val restatementsSkipped: Int = 0,
            /**
             * How many [com.kevin.legion.data.local.IngestMethod.UNRECONCILED]
             * rows this commit superseded (ticket 12 §3/§5) - zero for a
             * provisional commit itself (the guard in [commit] never lets an
             * UNRECONCILED file delete anything), and zero for a reconciled
             * commit whose window had no provisional rows waiting. Carried out
             * so the import surface can say "12 pending transactions replaced
             * by the statement" rather than silently shrinking a total.
             */
            val provisionalSuperseded: Int = 0,
        ) : CommitOutcome()
        data class Quarantined(val reason: String) : CommitOutcome()
        /**
         * Cutover 3. A gate-passed [LedgerIngestResult.Success] whose engine write failed AFTER
         * reconciliation - a real possibility a plain `Insert` DAO call never had (a corrupted field
         * schema, a reference-validation edge). The whole transaction rolled back: nothing landed,
         * not even the [IngestedFile] bookkeeping from a partial write, and [reason] is the worded
         * failure CLAUDE.md §7 requires rather than a false success. The file's own [IngestedFile]
         * record is written back to [IngestState.NEW] so it is re-offered on the next scan.
         */
        data class EngineWriteFailed(val reason: String) : CommitOutcome()
    }

    /**
     * Commits a [LedgerIngestResult] - win or quarantine - for one [staged]
     * file. On [LedgerIngestResult.Success]: runs the replace-flow deletion +
     * [com.kevin.legion.data.local.IngestedFileDao.resetOverlapping] FIRST
     * when [StageOutcome.Staged.isReplace] is set, then dedups
     * ([resolveDedup]) and inserts, all inside one Room transaction - ticket
     * 03 amendment 2's fix for the "YTD statement that legitimately
     * contributed zero net rows never comes back" silent-data-loss hole. On
     * [LedgerIngestResult.Quarantined]: nothing is written to the engine,
     * only the [IngestedFile] record.
     *
     * **Cutover 3**: every transaction row is written through [RecordStore], never the legacy
     * `ledger_transactions` table - see this object's own class doc for the full transaction/rollback
     * shape.
     *
     * [llmUsage] is `(promptTokens, responseTokens)` for the LLM path (null
     * fields allowed - a call that ran but got no usable response still
     * "attempted"), or null entirely for the deterministic path, which never
     * touches Gemini and so has nothing to record here.
     */
    suspend fun commit(
        context: Context,
        driveFileId: String,
        treeUri: String?,
        displayName: String,
        staged: StageOutcome.Staged,
        result: LedgerIngestResult,
        llmUsage: Pair<Int?, Int?>? = null,
    ): CommitOutcome {
        val db = CarDatabase.getDatabase(context)
        val ingestedDao = db.ingestedFileDao()
        val now = System.currentTimeMillis()
        val existing = ingestedDao.getByDriveFileId(driveFileId)

        return when (result) {
            is LedgerIngestResult.Quarantined -> {
                ingestedDao.upsert(
                    blank(existing, driveFileId, treeUri, displayName, now).copy(
                        displayName = displayName, state = IngestState.QUARANTINED,
                        quarantineReason = result.reason, lastAttemptAt = now,
                        llmAttempted = llmUsage != null || (existing?.llmAttempted ?: false),
                        llmPromptTokens = llmUsage?.first ?: existing?.llmPromptTokens,
                        llmResponseTokens = llmUsage?.second ?: existing?.llmResponseTokens,
                    )
                )
                CommitOutcome.Quarantined(result.reason)
            }
            is LedgerIngestResult.Success -> {
                val stamped = result.transactions.map { it.copy(sourceFileId = driveFileId) }
                val accountId = stamped.first().accountId
                val minDate = stamped.minOf { it.txnDate }
                val maxDate = stamped.maxOf { it.txnDate }

                val schema = LedgerAspectSeeder.ensureSeeded(context)
                val fieldIds = schema.transaction.fieldIds
                val recordStore = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())

                var inserted = 0
                var duplicatesSkipped = 0
                var restatementsSkipped = 0
                var provisionalSuperseded = 0
                try {
                    db.withTransaction {
                        if (staged.isReplace) {
                            // Engine-side equivalent of the retired
                            // `LedgerTransactionDao.deleteBySourceFileId` - every active engine
                            // Transaction record this file previously produced, trashed before the
                            // re-parsed replacement is inserted below.
                            val toReplace = db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId)
                                .filter { PayloadCodec.readString(JSONObject(it.payload), fieldIds.getValue(LedgerAspectSeeder.FIELD_SOURCE_FILE_ID)) == driveFileId }
                            for (rec in toReplace) {
                                when (recordStore.delete(rec.id)) {
                                    is RecordStore.DeleteResult.Trashed, is RecordStore.DeleteResult.AlreadyTrashed -> {}
                                    else -> throw EngineWriteFailedException("replace: couldn't remove this file's own previous row #${rec.id}")
                                }
                            }
                            val prevAccount = staged.previousAccountId
                            val prevMin = staged.previousMinTxnDate
                            val prevMax = staged.previousMaxTxnDate
                            if (prevAccount != null && prevMin != null && prevMax != null) {
                                ingestedDao.resetOverlapping(
                                    accountId = prevAccount, fileId = driveFileId,
                                    replacedMin = prevMin, replacedMax = prevMax,
                                )
                            }
                        }

                        // Ticket 12 §5 - three load-bearing properties, in order, now applied to the
                        // ENGINE mirror instead of the (retired) legacy delete:
                        //
                        // 1. THE GUARD. Without `ingestMethod != UNRECONCILED`, importing
                        //    the card CSV twice would make the second import delete the
                        //    first's own rows and then re-insert them - churn that looks
                        //    like data loss to any observer. A provisional file must
                        //    never supersede anything, including its own prior import.
                        // 2. BEFORE the dedup read below. If stale provisional rows were still
                        //    present, a reconciled statement's genuine rows would match them as
                        //    duplicates and get DROPPED - the verified row deleted in
                        //    favour of the unverified one it was meant to replace,
                        //    precisely backwards.
                        // 3. INSIDE the transaction. A crash between the delete and the
                        //    insert below must never leave the account with neither.
                        if (stamped.first().ingestMethod != IngestMethod.UNRECONCILED) {
                            val toSupersede = db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId)
                                .filter { rec ->
                                    if (rec.provenance != RecordProvenance.UNRECONCILED) return@filter false
                                    val payload = JSONObject(rec.payload)
                                    val rowAccountId = PayloadCodec.readString(payload, fieldIds.getValue(LedgerAspectSeeder.FIELD_ACCOUNT_ID)).orEmpty()
                                    val rowTxnDate = PayloadCodec.readLong(payload, fieldIds.getValue(LedgerAspectSeeder.FIELD_TXN_DATE))
                                    sameCard(rowAccountId, accountId) && rowTxnDate != null && rowTxnDate in minDate..maxDate
                                }
                            for (rec in toSupersede) {
                                when (recordStore.delete(rec.id)) {
                                    is RecordStore.DeleteResult.Trashed -> provisionalSuperseded++
                                    is RecordStore.DeleteResult.AlreadyTrashed -> {}
                                    else -> throw EngineWriteFailedException("supersede: couldn't remove provisional row #${rec.id}")
                                }
                            }
                        }

                        // Read INSIDE the transaction and AFTER the replace/supersede deletes above -
                        // both can remove rows the dedup comparison must not see as "already there".
                        val existingRows = db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId)
                            .map { LedgerRecordBridge.toTransaction(it, fieldIds) }
                            .filter { it.accountId == accountId && it.txnDate in minDate..maxDate }
                        // ingested_files stays plugin-internal and legacy - unaffected by cutover 3,
                        // read INSIDE the transaction and AFTER the replace-flow reset above: that
                        // reset can knock an overlapping file out of INGESTED, and a window from a
                        // file that is no longer committed would let this drop rows nothing else is
                        // going to put back.
                        val enumerated = ingestedDao
                            .enumeratedWindows(accountId, driveFileId, minDate, maxDate)
                            .map { LedgerCoveredWindow(it.fromMs, it.toMs) }
                        val resolution = resolveDedup(existingRows, stamped, enumerated)

                        for (txn in resolution.toInsert) {
                            val res = recordStore.create(
                                recordTypeId = schema.transaction.recordTypeId,
                                fieldValues = LedgerRecordBridge.fieldValuesFor(txn, fieldIds),
                                provenance = LedgerRecordBridge.provenanceFor(txn.ingestMethod),
                                now = txn.txnDate,
                                guid = txn.syncId,
                            )
                            if (res !is RecordStore.WriteResult.Success) {
                                throw EngineWriteFailedException(
                                    "row '${txn.description}': ${(res as RecordStore.WriteResult.Failure).reason}",
                                )
                            }
                            inserted++
                        }
                        duplicatesSkipped = resolution.duplicatesSkipped
                        restatementsSkipped = resolution.restatementsSkipped

                        ingestedDao.upsert(
                            blank(existing, driveFileId, treeUri, displayName, now).copy(
                                displayName = displayName, state = IngestState.INGESTED,
                                quarantineReason = null, transactionCount = inserted,
                                accountId = accountId, minTxnDate = minDate, maxTxnDate = maxDate,
                                duplicatesSkipped = duplicatesSkipped, lastAttemptAt = now,
                                llmAttempted = llmUsage != null || (existing?.llmAttempted ?: false),
                                llmPromptTokens = llmUsage?.first ?: existing?.llmPromptTokens,
                                llmResponseTokens = llmUsage?.second ?: existing?.llmResponseTokens,
                            )
                        )
                    }
                } catch (e: EngineWriteFailedException) {
                    Log.w(TAG, "commit: engine write failed after the gate passed, rolled back - ${e.reason}")
                    // Nothing landed for this file, not even the IngestedFile bookkeeping - that
                    // write was inside the same rolled-back transaction. Written back to NEW, outside
                    // the transaction, so the file is re-offered on the next scan rather than
                    // vanishing (a worded failure and a retry path, never a silent drop).
                    ingestedDao.upsert(
                        blank(existing, driveFileId, treeUri, displayName, now).copy(
                            displayName = displayName, state = IngestState.NEW,
                            quarantineReason = null, lastAttemptAt = now,
                        )
                    )
                    return CommitOutcome.EngineWriteFailed(e.reason)
                }
                CommitOutcome.Ingested(inserted, duplicatesSkipped, restatementsSkipped, provisionalSuperseded)
            }
        }
    }

    private const val TAG = "IngestPipeline"

    /**
     * Marks a staged file [IngestState.NEEDS_LLM] (ticket 06 amendment 3) so
     * a decline is "not now", never "never" - re-offered at every future scan
     * until approved or the file's content changes, rather than being
     * skipped forever (a record with any other state) or forgotten entirely
     * (no record at all).
     */
    suspend fun markNeedsLlm(context: Context, driveFileId: String, treeUri: String?, displayName: String) {
        val dao = CarDatabase.getDatabase(context).ingestedFileDao()
        val now = System.currentTimeMillis()
        val existing = dao.getByDriveFileId(driveFileId)
        dao.upsert(
            blank(existing, driveFileId, treeUri, displayName, now).copy(
                displayName = displayName, state = IngestState.NEEDS_LLM, lastAttemptAt = now,
            )
        )
    }
}
