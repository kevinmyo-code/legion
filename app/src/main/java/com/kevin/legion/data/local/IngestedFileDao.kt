package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * Data Access Object for [IngestedFile]. Per ticket 03's resolution the record's
 * job is work avoidance, not correctness, so these queries stay simple and
 * inspectable rather than trying to encode the whole scan/replace state machine
 * in SQL.
 */
@Dao
interface IngestedFileDao {
    /** The skip-filter lookup: does a record already exist for this file id, and if so what does it say. */
    @Query("SELECT * FROM ingested_files WHERE driveFileId = :driveFileId")
    suspend fun getByDriveFileId(driveFileId: String): IngestedFile?

    /**
     * Content-identity lookup for the hash-before-parse step: is this content
     * already known under an [IngestState.INGESTED] file (possibly a different
     * name/id)? Deliberately scoped to INGESTED only - a QUARANTINED or
     * UNREADABLE file's hash matching is not "this content is already in the
     * ledger", so it must not short-circuit into DUPLICATE_CONTENT.
     */
    @Query(
        "SELECT * FROM ingested_files WHERE contentSha256 = :contentSha256 " +
            "AND state = 'INGESTED' LIMIT 1"
    )
    suspend fun findIngestedByContentHash(contentSha256: String): IngestedFile?

    /** Every record a given connected folder has produced, for a scan pass over one [IngestedFile.treeUri]. */
    @Query("SELECT * FROM ingested_files WHERE treeUri = :treeUri")
    suspend fun listByTreeUri(treeUri: String): List<IngestedFile>

    /** Insert-or-replace by [IngestedFile.driveFileId] - the scan's single write path for a file's record. */
    @Upsert
    suspend fun upsert(file: IngestedFile)

    /**
     * Amendment 2's replace-flow reset: when a file's rows are deleted and
     * re-ingested, any OTHER already-[IngestState.INGESTED] file for the same
     * account whose date range overlaps the replaced range must go back to
     * [IngestState.NEW] so the next scan re-derives what it should have
     * contributed. Without this a year-to-date statement that legitimately
     * contributed zero net rows (ticket 04's per-tuple dedup) would never be
     * re-scanned and its transactions would never come back.
     *
     * This UPDATEs rather than DELETEs. Deleting would discard
     * [IngestedFile.duplicatesSkipped] and the measured
     * [IngestedFile.llmPromptTokens]/[IngestedFile.llmResponseTokens], which
     * tickets 04 and 06 added specifically as audit history, and would
     * contradict ticket 03's "records are NEVER pruned".
     */
    @Query(
        "UPDATE ingested_files SET state = 'NEW' WHERE accountId = :accountId " +
            "AND driveFileId != :fileId AND state = 'INGESTED' " +
            "AND minTxnDate <= :replacedMax AND maxTxnDate >= :replacedMin"
    )
    suspend fun resetOverlapping(accountId: String, fileId: String, replacedMin: Long, replacedMax: Long)

    /**
     * Measured average token usage across every LLM call so far, for ticket
     * 06's spend estimate to use a MEASURED number instead of a reasoned one
     * once at least one real batch has run (§6: "after one real batch the app
     * holds measured token counts, so the estimate stops being a reasoned
     * number derived from a reasoned number"). Both fields are null (not
     * zero) until at least one row has non-null token counts, which
     * [IngestScanner] reads as "no measured data yet, fall back to the
     * reasoned constant".
     */
    @Query(
        "SELECT AVG(llmPromptTokens) as avgPrompt, AVG(llmResponseTokens) as avgResponse " +
            "FROM ingested_files WHERE llmAttempted = 1 AND llmPromptTokens IS NOT NULL"
    )
    suspend fun averageLlmTokenUsage(): LlmTokenAverage?
}

/** Aggregate-query projection for [IngestedFileDao.averageLlmTokenUsage]. */
data class LlmTokenAverage(val avgPrompt: Double?, val avgResponse: Double?)
