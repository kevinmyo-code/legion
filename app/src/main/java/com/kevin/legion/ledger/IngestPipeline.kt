package com.kevin.legion.ledger

import android.content.Context
import androidx.room.withTransaction
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestState
import com.kevin.legion.data.local.IngestedFile
import java.security.MessageDigest

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
 */
object IngestPipeline {

    /**
     * [driveFileId] with the `acc=N;` prefix stripped, per ticket 03
     * resolution §1: that prefix is a positional local-account index, not
     * part of the file's identity, and would make the key unstable across a
     * second signed-in account for a reason unrelated to the file itself.
     */
    fun stripAccountPrefix(documentId: String): String =
        documentId.replaceFirst(Regex("^acc=[^;]*;"), "")

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
        data class Ingested(val transactionCount: Int, val duplicatesSkipped: Int) : CommitOutcome()
        data class Quarantined(val reason: String) : CommitOutcome()
    }

    /**
     * Commits a [LedgerIngestResult] - win or quarantine - for one [staged]
     * file. On [LedgerIngestResult.Success]: runs the replace-flow deletion +
     * [com.kevin.legion.data.local.IngestedFileDao.resetOverlapping] FIRST
     * when [StageOutcome.Staged.isReplace] is set, then dedups
     * ([resolveDedup]) and inserts, all inside one Room transaction - ticket
     * 03 amendment 2's fix for the "YTD statement that legitimately
     * contributed zero net rows never comes back" silent-data-loss hole. On
     * [LedgerIngestResult.Quarantined]: nothing is written to
     * `ledger_transactions`, only the [IngestedFile] record.
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
        val txnDao = db.ledgerTransactionDao()
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

                var inserted = 0
                var duplicatesSkipped = 0
                db.withTransaction {
                    if (staged.isReplace) {
                        txnDao.deleteBySourceFileId(driveFileId)
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

                    val existingRows = txnDao.getForAccountInRange(accountId, minDate, maxDate)
                    val resolution = resolveDedup(existingRows, stamped)
                    if (resolution.toInsert.isNotEmpty()) txnDao.insertAll(resolution.toInsert)
                    inserted = resolution.toInsert.size
                    duplicatesSkipped = resolution.duplicatesSkipped

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
                CommitOutcome.Ingested(inserted, duplicatesSkipped)
            }
        }
    }

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
