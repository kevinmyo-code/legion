package com.kevin.legion.ledger

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.IngestState
import com.kevin.legion.data.local.IngestedFile
import com.kevin.legion.engine.migration.EngineLedgerRetirementCopy
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
 *
 * **Engine retirement step 5** (`.scratch/backend-erp/issues/15-engine-retirement-sequence.md`), the
 * "before" half of ticket 03's "lands before or with the `commit_statement` RPC move". Cutover 3
 * (`docs/architecture/cutover3-2026-08-24.md`, 2026-08-24) had repointed [commit]'s `Success` branch
 * onto the engine; this step repoints it back onto
 * [com.kevin.legion.data.local.LedgerTransactionDao.insertAll] against the legacy
 * `ledger_transactions` table - the ONLY local store an unconfigured (no Supabase project)
 * clone-and-run install has. [ensureLegacyReconciled] runs [EngineLedgerRetirementCopy] once,
 * first, so any transaction written directly through the engine between the cutover and this
 * repoint is not silently invisible to the dedup/supersede reads below.
 *
 * CLAUDE.md §4 rule 7's provisional-row supersession runs INSIDE this same transaction, exactly as
 * it did pre-cutover-3 and exactly as `.scratch/backend-erp/issues/03-the-gate-server-side.md`'s
 * ruling 7 requires of its eventual RPC successor: when a reconciled (`DETERMINISTIC`/
 * `LLM_RECONCILED`) file commits,
 * [com.kevin.legion.data.local.LedgerTransactionDao.deleteSupersededProvisional] (suffix-matched on
 * the physical card via [sameCard], exactly as ticket 12 §0 requires) removes every
 * `UNRECONCILED` row for the same card whose `txnDate` falls inside the incoming statement's own
 * range, BEFORE the dedup read, in the same transaction as the inserts that follow - the three
 * load-bearing properties this file's own commit doc comment states below (the guard, the
 * before-dedup-read ordering, the inside-the-transaction atomicity). `ingested_files` stays
 * plugin-internal, unaffected by this step - its own writes inside this same transaction are
 * unchanged.
 *
 * **A genuine write failure is still WORDED, never left to throw uncaught.** A plain Room `@Insert`/
 * `@Query` against a typed table has a much narrower failure surface than the engine's per-row
 * JSON-schema validation did (there is no [com.kevin.legion.engine.RecordStore.WriteResult.Failure]
 * class of error here - a legacy `INSERT` either succeeds or SQLite throws), but CLAUDE.md §7 does
 * not carve out an exception for "the failure surface got narrower": [commit]'s whole
 * `db.withTransaction` block is still wrapped in a `try`/`catch`, and any thrown exception - the
 * transaction already rolled back by Room - is turned into [CommitOutcome.EngineWriteFailed] rather
 * than propagating. The file's [IngestedFile] record is written back to [IngestState.NEW] OUTSIDE
 * the rolled-back transaction, so the file is re-offered on the next scan rather than silently
 * vanishing. See this object's own `commit` doc comment for why this caller chain in particular
 * cannot be allowed to throw: `import_statement` only opens a screen, and the real write runs from
 * a bare `LaunchedEffect` with no try/catch of its own.
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
         * A gate-passed [LedgerIngestResult.Success] whose legacy-table write failed AFTER
         * reconciliation - a genuine Room/SQLite failure (constraint violation, disk I/O), the same
         * narrow surface [com.kevin.legion.pantry.PantryController.writeReceipt] guards against on
         * its own legacy write. The whole transaction rolled back: nothing landed, not even the
         * [IngestedFile] bookkeeping from a partial write, and [reason] is the worded failure
         * CLAUDE.md §7 requires rather than a false success or a raw throw. The file's own
         * [IngestedFile] record is written back to [IngestState.NEW] so it is re-offered on the
         * next scan. Name kept from cutover 3 rather than renamed - a caller reading
         * `CommitOutcome.EngineWriteFailed` still gets the correct meaning ("the write layer
         * failed"), and renaming it would touch every caller/test for no behavioural gain.
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
     * **Engine retirement step 5**: every transaction row is written through
     * [com.kevin.legion.data.local.LedgerTransactionDao] again, never the engine - see this
     * object's own class doc for the full transaction/rollback shape and why this is the "before"
     * half of ticket 03's RPC pairing rather than a reversal of it.
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
                // Before EVER reading or writing `ledger_transactions` from this path - see
                // EngineLedgerRetirementCopy's own class doc for why this has to run here too, not
                // only from LedgerController: IngestScanner's folder-scan path calls this function
                // directly and never goes through LedgerController at all.
                EngineLedgerRetirementCopy.copyIfNeeded(context)

                val stamped = result.transactions.map { it.copy(sourceFileId = driveFileId) }
                val accountId = stamped.first().accountId
                val minDate = stamped.minOf { it.txnDate }
                val maxDate = stamped.maxOf { it.txnDate }
                val txnDao = db.ledgerTransactionDao()

                var inserted = 0
                var duplicatesSkipped = 0
                var restatementsSkipped = 0
                var provisionalSuperseded = 0
                try {
                    db.withTransaction {
                        if (staged.isReplace) {
                            // Every row this file previously produced, dropped before the
                            // re-parsed replacement is inserted below.
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

                        // Ticket 12 §5 - three load-bearing properties, in order, now a single SQL
                        // predicate again ([LedgerTransactionDao.deleteSupersededProvisional]) rather
                        // than a Kotlin-side filter-and-delete loop over engine records:
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
                            provisionalSuperseded = txnDao.deleteSupersededProvisional(accountId, minDate, maxDate)
                        }

                        // Read INSIDE the transaction and AFTER the replace/supersede deletes above -
                        // both can remove rows the dedup comparison must not see as "already there".
                        val existingRows = txnDao.getForAccountInRange(accountId, minDate, maxDate)
                        // ingested_files stays plugin-internal, read INSIDE the transaction and AFTER
                        // the replace-flow reset above: that reset can knock an overlapping file out
                        // of INGESTED, and a window from a file that is no longer committed would let
                        // this drop rows nothing else is going to put back.
                        val enumerated = ingestedDao
                            .enumeratedWindows(accountId, driveFileId, minDate, maxDate)
                            .map { LedgerCoveredWindow(it.fromMs, it.toMs) }
                        val resolution = resolveDedup(existingRows, stamped, enumerated)

                        if (resolution.toInsert.isNotEmpty()) txnDao.insertAll(resolution.toInsert)
                        inserted = resolution.toInsert.size
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
                } catch (e: Exception) {
                    Log.w(TAG, "commit: legacy write failed after the gate passed, rolled back - ${e.message}")
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
                    return CommitOutcome.EngineWriteFailed(e.message ?: "couldn't save this statement")
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
