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

    /**
     * Every [IngestState.INGESTED] file whose [IngestedFile.treeUri] is non-null - the exact
     * scope ticket 12's dry run (`.scratch/backend-erp/issues/12-ledger-rows-have-no-statement-
     * header.md`) re-reads: only a file that came from a connected folder scan is reachable again
     * by that same [DocumentsContract] identity, which single-file `ACTION_OPEN_DOCUMENT` picks
     * (null [IngestedFile.treeUri]) are not - see [com.kevin.legion.ledger.ReingestDryRun]'s own
     * class doc for why that narrower set is the honest one to attempt.
     */
    @Query("SELECT * FROM ingested_files WHERE state = 'INGESTED' AND treeUri IS NOT NULL")
    suspend fun listIngestedWithTreeUri(): List<IngestedFile>

    /** Insert-or-replace by [IngestedFile.driveFileId] - the scan's single write path for a file's record. */
    @Upsert
    suspend fun upsert(file: IngestedFile)

    /**
     * Every currently-quarantined record, for ticket 08's quarantine surface
     * (resolution §6). Newest attempt first so a recently-failed statement
     * surfaces above one that has sat quarantined for weeks. Deliberately a
     * plain state filter, not a join against [LedgerTransactionDao] - per
     * [IngestedFile]'s own doc comment, [IngestState.QUARANTINED] means
     * nothing was ever written for this file, so there is nothing to join.
     */
    @Query("SELECT * FROM ingested_files WHERE state = 'QUARANTINED' ORDER BY lastAttemptAt DESC")
    suspend fun listQuarantined(): List<IngestedFile>

    /**
     * Just the count of [listQuarantined] - mission-control ticket 04's ALARM segment build. The
     * shell status line (`MainActivity.kt`'s `LegionShell`) polls this every `STATUS_POLL_MS` to
     * decide whether `StatusLine`'s `alarmCount` is nonzero; it does not need the rows themselves,
     * only whether there are any, so this is a `COUNT(*)` rather than reusing [listQuarantined] and
     * throwing the list away. Same `state = 'QUARANTINED'` filter, same table, cheap either way -
     * `ingested_files` is a per-account-per-file ledger, not a row-per-transaction table.
     *
     * **This is the ALARM tier's ONLY source.** Ticket 04's other named ALARM example, an active
     * vehicle fault (DTC), is deliberately NOT wired here or anywhere else the shell polls - see
     * `TodayGapResolvers.kt`'s `buildAlertRows` doc, which already states the reason in writing: a
     * DTC read is a live OBD scan, not persisted state anything can cheaply poll. Do not "complete"
     * this by adding a DTC read to the shell poll; that would turn a cheap indexed count into a
     * Bluetooth round trip on a timer.
     */
    @Query("SELECT COUNT(*) FROM ingested_files WHERE state = 'QUARANTINED'")
    suspend fun countQuarantined(): Int

    /**
     * The explicit-retry transition from [IngestedFile]'s own state diagram:
     * `QUARANTINED --explicit user retry--> NEW`. Only flips the state back
     * to [IngestState.NEW] so the next scan re-examines the file; it does
     * NOT re-read or re-parse anything itself - ticket 08 Part 5 (the read
     * surfaces) stops here on purpose, since driving an actual re-parse
     * means calling [com.kevin.legion.service.IngestScanner.scan], which is
     * ticket 08 Part 6's job. Guarded to only affect a row that is actually
     * [IngestState.QUARANTINED], so retrying twice in a row (a double-tap)
     * is a harmless no-op the second time rather than clobbering a state a
     * scan may have already moved on.
     */
    @Query("UPDATE ingested_files SET state = 'NEW' WHERE driveFileId = :driveFileId AND state = 'QUARANTINED'")
    suspend fun retryQuarantined(driveFileId: String)

    /**
     * The same explicit-retry transition as [retryQuarantined], applied to
     * every quarantined record at once, and returning how many rows it moved.
     *
     * This exists because a quarantine reason can go stale: a record says why
     * THAT BUILD's parser failed, and a later build can fix the layout bug
     * without anything re-examining the files it already rejected. Measured on
     * Kevin's device 2026-08-06 - 35 records written by the 08-02 build, whose
     * statements the 08-03 parsers read cleanly, sat quarantined because the
     * only way back was tapping RETRY 35 times.
     *
     * Same guard as the single-file version (`state = 'QUARANTINED'`), so this
     * can never drag an [IngestState.INGESTED] record backwards, and the same
     * deliberate split: it flips state only. Re-reading and re-parsing is the
     * next scan's job.
     */
    @Query("UPDATE ingested_files SET state = 'NEW' WHERE state = 'QUARANTINED'")
    suspend fun retryAllQuarantined(): Int

    /**
     * Every file record, unconditionally. Only ever called from
     * [com.kevin.legion.ledger.LedgerController.purgeAll]. Wiping this table
     * is what makes a folder rescan re-offer files it has already seen - the
     * transactions alone are not enough, because `driveFileId` records are
     * what the scanner skips on.
     */
    @Query("DELETE FROM ingested_files")
    suspend fun deleteAll(): Int

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
     * The date spans OTHER already-committed files for this account have
     * enumerated completely, restricted to those overlapping [fromMs]..[toMs].
     *
     * Feeds [com.kevin.legion.ledger.resolveDedup]'s second pass (2026-08-04):
     * inside one of these spans, an incoming transaction matching an existing
     * one on account/date/cents is the same transaction worded differently by
     * a second export, not a second purchase - see that function's doc for why
     * that inference is only safe here.
     *
     * `state = 'INGESTED'` is the load-bearing filter, not boilerplate. Only an
     * INGESTED file passed the reconciliation gate, and only a file that passed
     * the gate can claim to have listed everything in its own date range; a
     * QUARANTINED file wrote no rows at all, and a file still in NEW may have
     * written some and be about to write more. `driveFileId != :fileId` keeps a
     * re-import of the SAME file from treating its own previous window as
     * someone else's testimony.
     */
    @Query(
        "SELECT minTxnDate AS fromMs, maxTxnDate AS toMs FROM ingested_files " +
            "WHERE accountId = :accountId AND driveFileId != :fileId AND state = 'INGESTED' " +
            "AND minTxnDate IS NOT NULL AND maxTxnDate IS NOT NULL " +
            "AND minTxnDate <= :toMs AND maxTxnDate >= :fromMs"
    )
    suspend fun enumeratedWindows(
        accountId: String,
        fileId: String,
        fromMs: Long,
        toMs: Long,
    ): List<CoveredWindowRow>

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

    /**
     * §0's coverage query (`.scratch/ledger-pnl/issues/01-entity-profit-and-loss.md`): every
     * INGESTED file's `[minTxnDate, maxTxnDate]` overlapping `[fromMs, toMs]`, one row per file - an
     * account with two overlapping statements in range produces two rows here, which
     * [com.kevin.legion.ledger.LedgerController.budgetVsActual] unions per `accountId` into one
     * [com.kevin.legion.ledger.AccountCoverage].
     *
     * `state = 'INGESTED'` is the load-bearing filter, same reasoning as [enumeratedWindows]'s own
     * doc comment: only an INGESTED file passed the reconciliation gate, and only a file that passed
     * the gate can claim its date span is COMPLETE - a QUARANTINED file wrote nothing, and a file
     * still NEW may write more later. A P&L's coverage claim is only as true as that gate.
     *
     * `accountId IS NOT NULL` because [com.kevin.legion.data.local.IngestedFile.accountId] is only
     * known once a file has actually been parsed (amendment 2's doc comment) - a row that predates
     * that, or a defensive placeholder, can never be attributed to a real account and must not be
     * counted as coverage for one.
     */
    @Query(
        "SELECT accountId, minTxnDate AS fromMs, maxTxnDate AS toMs FROM ingested_files " +
            "WHERE state = 'INGESTED' AND accountId IS NOT NULL " +
            "AND minTxnDate <= :toMs AND maxTxnDate >= :fromMs"
    )
    suspend fun coverageInRange(fromMs: Long, toMs: Long): List<AccountCoverageRow>
}

/** Aggregate-query projection for [IngestedFileDao.averageLlmTokenUsage]. */
data class LlmTokenAverage(val avgPrompt: Double?, val avgResponse: Double?)

/**
 * Query projection for [IngestedFileDao.enumeratedWindows] - NOT an `@Entity`,
 * so it must never be added to [CarDatabase]'s entity list (same posture as
 * [PidSummary]). Non-null because the query filters both columns `IS NOT NULL`.
 */
data class CoveredWindowRow(val fromMs: Long, val toMs: Long)

/**
 * Query projection for [IngestedFileDao.coverageInRange] - NOT an `@Entity`,
 * so it must never be added to [CarDatabase]'s entity list (same posture as
 * [CoveredWindowRow]/[PidSummary]). One row per INGESTED file overlapping the
 * range; [com.kevin.legion.ledger.LedgerController.budgetVsActual] unions rows
 * sharing an `accountId` into one [com.kevin.legion.ledger.AccountCoverage].
 */
data class AccountCoverageRow(val accountId: String, val fromMs: Long, val toMs: Long)
