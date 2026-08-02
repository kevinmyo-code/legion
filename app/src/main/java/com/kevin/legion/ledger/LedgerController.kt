package com.kevin.legion.ledger

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.data.local.LedgerTransactionDao
import com.kevin.legion.ledger.parsers.DeterministicResult
import com.kevin.legion.ledger.parsers.PdfWords
import com.kevin.legion.ledger.parsers.StatementDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates ledger statement ingestion - mirrors
 * [com.kevin.legion.vehicle.VehicleController]/
 * [com.kevin.legion.vehicle.BuildSheetController]'s naming and shape.
 * `.claude/plans/wiggly-beaming-quasar.md`.
 */
object LedgerController {
    private const val TAG = "LedgerController"

    /**
     * Reads [uri] and runs it through [IngestPipeline] as a **one-element
     * run through the same pipeline** the folder scan uses -
     * `.scratch/ledger-drive-ingestion/issues/05-batch-ingestion-mechanics.md`
     * resolution §8. [uri] is expected to be a SAF document URI (the result
     * of `ACTION_OPEN_DOCUMENT`), so its own document id becomes the
     * [com.kevin.legion.data.local.IngestedFile.driveFileId] - a hand-picked
     * file gets a real record, content hash and `sourceFileId` exactly like a
     * scanned one, with `treeUri = null` marking "arrived via a single-file
     * pick" (ticket 03 amendment 1). Concrete payoff: pick a statement by
     * hand today, and if that same file later turns up in a connected
     * folder, the hash check recognises it and records `DUPLICATE_CONTENT`
     * instead of re-parsing (and possibly re-paying for) it.
     *
     * Falls back to the pre-ticket-05 dedup-only behavior (no [IngestedFile]
     * bookkeeping) for the rare case [uri] isn't a SAF document URI at all -
     * this must never crash an import over an identity it can't derive.
     */
    suspend fun importStatement(context: Context, uri: Uri): LedgerImportResult = withContext(Dispatchers.IO) {
        PdfWords.init(context)

        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "failed to read $uri: ${e.message}")
            null
        } ?: return@withContext LedgerImportResult(
            success = false,
            message = "I couldn't read that file - try picking it again.",
        )

        val fileName = queryDisplayName(context, uri) ?: "statement.pdf"
        val driveFileId = documentIdFor(uri)?.let { IngestPipeline.stripAccountPrefix(it) }
            ?: return@withContext importWithoutRecord(context, fileName, bytes)

        val lastModified = queryLastModified(context, uri)
        when (val staged = IngestPipeline.stage(
            context = context,
            driveFileId = driveFileId,
            treeUri = null,
            displayName = fileName,
            sizeBytes = bytes.size.toLong(),
            lastModified = lastModified,
            mimeType = "application/pdf",
        ) { bytes }) {
            is IngestPipeline.StageOutcome.Skipped -> LedgerImportResult(
                success = false,
                message = "I've already imported this exact file - nothing new to add.",
            )
            is IngestPipeline.StageOutcome.DuplicateContent -> LedgerImportResult(
                success = false,
                message = "This file's contents match a statement I've already imported.",
            )
            is IngestPipeline.StageOutcome.Unreadable -> LedgerImportResult(
                success = false,
                message = "I couldn't read that as a statement: ${staged.reason}",
            )
            is IngestPipeline.StageOutcome.Staged -> when (
                val det = StatementDispatcher.dispatchDeterministic(fileName, bytes)
            ) {
                is DeterministicResult.Success -> commitResult(
                    context, driveFileId, fileName, staged, LedgerIngestResult.Success(det.transactions),
                )
                is DeterministicResult.Quarantined -> {
                    IngestPipeline.commit(
                        context, driveFileId, null, fileName, staged,
                        LedgerIngestResult.Quarantined(det.reason),
                    )
                    LedgerImportResult(success = false, message = det.reason)
                }
                is DeterministicResult.NeedsLlm -> {
                    // No approval surface exists yet for a single hand-picked
                    // file (ticket 06's gate UI belongs to ticket 08, out of
                    // scope here). NEEDS_LLM is still the correct terminal
                    // state either way: the gate's rule is "ask every time,
                    // never silently spend" (ticket 06 resolution §5), so
                    // this must never auto-approve. The file is left exactly
                    // where a decline in the folder-scan gate would leave it
                    // - re-offered on the next scan, not lost.
                    IngestPipeline.markNeedsLlm(context, driveFileId, null, fileName)
                    LedgerImportResult(
                        success = false,
                        message = "This statement doesn't match a known layout and needs AI reading, " +
                            "which uses your own Gemini key - connect a statements folder and approve " +
                            "it from a scan.",
                    )
                }
            }
        }
    }

    private suspend fun commitResult(
        context: Context,
        driveFileId: String,
        fileName: String,
        staged: IngestPipeline.StageOutcome.Staged,
        result: LedgerIngestResult,
    ): LedgerImportResult = when (
        val outcome = IngestPipeline.commit(context, driveFileId, null, fileName, staged, result)
    ) {
        is IngestPipeline.CommitOutcome.Ingested -> {
            val message = buildString {
                append("Imported ${outcome.transactionCount} transaction(s) from $fileName.")
                if (outcome.duplicatesSkipped > 0) append(" (${outcome.duplicatesSkipped} already on file, skipped.)")
            }
            LedgerImportResult(success = true, message = message, importedCount = outcome.transactionCount)
        }
        is IngestPipeline.CommitOutcome.Quarantined -> LedgerImportResult(success = false, message = outcome.reason)
    }

    /**
     * Pre-ticket-05 fallback for a [Uri] that isn't a SAF document URI, so
     * there is no stable id to key an [com.kevin.legion.data.local.IngestedFile]
     * record on. Only [resolveDedup]'s transaction-level dedup applies here -
     * no file-level skip/duplicate/replace bookkeeping. Expected to be rare
     * in practice (`ACTION_OPEN_DOCUMENT` results are SAF document URIs), but
     * an import must never crash over an identity it can't derive.
     */
    private suspend fun importWithoutRecord(context: Context, fileName: String, bytes: ByteArray): LedgerImportResult =
        when (val det = StatementDispatcher.dispatchDeterministic(fileName, bytes)) {
            is DeterministicResult.Quarantined -> LedgerImportResult(success = false, message = det.reason)
            is DeterministicResult.Success -> commitPlain(context, fileName, det.transactions)
            is DeterministicResult.NeedsLlm -> {
                val llm = StatementDispatcher.runLlm(fileName, det.statementText)
                when (val result = llm.result) {
                    is LedgerIngestResult.Success -> commitPlain(context, fileName, result.transactions)
                    is LedgerIngestResult.Quarantined -> LedgerImportResult(success = false, message = result.reason)
                }
            }
        }

    private suspend fun commitPlain(
        context: Context,
        fileName: String,
        transactions: List<LedgerTransaction>,
    ): LedgerImportResult {
        val dao = CarDatabase.getDatabase(context).ledgerTransactionDao()
        val (fresh, skipped) = dedupAgainstExisting(dao, transactions)
        if (fresh.isNotEmpty()) dao.insertAll(fresh)
        val message = buildString {
            append("Imported ${fresh.size} transaction(s) from $fileName.")
            if (skipped > 0) append(" ($skipped already on file, skipped.)")
        }
        return LedgerImportResult(success = true, message = message, importedCount = fresh.size)
    }

    suspend fun latestBalanceCents(context: Context, accountId: String): Long? =
        CarDatabase.getDatabase(context).ledgerTransactionDao().latestBalanceCents(accountId)

    suspend fun allAccountIds(context: Context): List<String> =
        CarDatabase.getDatabase(context).ledgerTransactionDao().allAccountIds()

    suspend fun recentTransactions(context: Context, limit: Int = 20): List<LedgerTransaction> =
        CarDatabase.getDatabase(context).ledgerTransactionDao().getRecent(limit)

    /**
     * Fetches the existing-row candidate set per account across [incoming]'s
     * own date range, then hands off to [resolveDedup] - ticket 04's pure
     * per-tuple counting comparison, run in Kotlin rather than SQL. Grouped by
     * [LedgerTransaction.accountId] first because a single statement is one
     * account in practice, but this stays correct even if a future producer
     * ever mixes them. Returns the rows to insert and how many were dropped as
     * duplicates. Only used by [importWithoutRecord] now - [IngestPipeline.commit]
     * does the equivalent for anything with an [com.kevin.legion.data.local.IngestedFile] record.
     */
    private suspend fun dedupAgainstExisting(
        dao: LedgerTransactionDao,
        incoming: List<LedgerTransaction>,
    ): Pair<List<LedgerTransaction>, Int> {
        val toInsert = mutableListOf<LedgerTransaction>()
        var skipped = 0
        for ((accountId, group) in incoming.groupBy { it.accountId }) {
            val minDate = group.minOf { it.txnDate }
            val maxDate = group.maxOf { it.txnDate }
            val existing = dao.getForAccountInRange(accountId, minDate, maxDate)
            val resolution = resolveDedup(existing, group)
            toInsert += resolution.toInsert
            skipped += resolution.duplicatesSkipped
        }
        return toInsert to skipped
    }

    /** Best-effort human-readable filename for the import confirmation message. */
    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    /** [uri]'s SAF document id, or null if it isn't a document URI at all (not expected for an `ACTION_OPEN_DOCUMENT` result, but never crash the import over it). */
    private fun documentIdFor(uri: Uri): String? = try {
        DocumentsContract.getDocumentId(uri)
    } catch (e: Exception) {
        null
    }

    /** Best-effort last-modified for [uri], used only as a change signal - 0L (never used for identity) if the provider doesn't report one. */
    private fun queryLastModified(context: Context, uri: Uri): Long = try {
        context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null,
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L } ?: 0L
    } catch (e: Exception) {
        0L
    }
}

data class LedgerImportResult(val success: Boolean, val message: String, val importedCount: Int = 0)
