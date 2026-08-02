package com.kevin.legion.ledger

import android.content.Context
import android.net.Uri
import android.util.Log
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.data.local.LedgerTransactionDao
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
     * Reads [uri], dispatches it through [StatementDispatcher], and - only on a
     * fully-reconciled result - dedupes against existing rows and inserts the
     * new ones. Reconciliation is atomic per statement: either every
     * transaction commits, or nothing does.
     *
     * [sourceFileId] stamps [LedgerTransaction.sourceFileId] on every inserted
     * row when the caller has one (the folder-scan/replace pipeline, ticket
     * 03); a single-file pick passes null, matching that column's own
     * "null for anything imported through a path that predates the scan
     * pipeline" contract.
     */
    suspend fun importStatement(
        context: Context,
        uri: Uri,
        sourceFileId: String? = null,
    ): LedgerImportResult = withContext(Dispatchers.IO) {
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

        when (val result = StatementDispatcher.dispatch(fileName, bytes)) {
            is LedgerIngestResult.Quarantined -> LedgerImportResult(success = false, message = result.reason)
            is LedgerIngestResult.Success -> {
                val dao = CarDatabase.getDatabase(context).ledgerTransactionDao()
                val (fresh, skipped) = dedupAgainstExisting(dao, result.transactions)
                val stamped = if (sourceFileId != null) fresh.map { it.copy(sourceFileId = sourceFileId) } else fresh
                if (stamped.isNotEmpty()) dao.insertAll(stamped)
                val message = buildString {
                    append("Imported ${stamped.size} transaction(s) from $fileName.")
                    if (skipped > 0) append(" ($skipped already on file, skipped.)")
                }
                LedgerImportResult(success = true, message = message, importedCount = stamped.size)
            }
        }
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
     * duplicates.
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
}

data class LedgerImportResult(val success: Boolean, val message: String, val importedCount: Int = 0)
