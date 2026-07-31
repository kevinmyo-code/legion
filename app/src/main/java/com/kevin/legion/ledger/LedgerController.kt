package com.kevin.legion.ledger

import android.content.Context
import android.net.Uri
import android.util.Log
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.LedgerTransaction
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

        when (val result = StatementDispatcher.dispatch(fileName, bytes)) {
            is LedgerIngestResult.Quarantined -> LedgerImportResult(success = false, message = result.reason)
            is LedgerIngestResult.Success -> {
                val dao = CarDatabase.getDatabase(context).ledgerTransactionDao()
                val fresh = result.transactions.filterNot { isDuplicate(dao, it) }
                if (fresh.isNotEmpty()) dao.insertAll(fresh)
                val skipped = result.transactions.size - fresh.size
                val message = buildString {
                    append("Imported ${fresh.size} transaction(s) from $fileName.")
                    if (skipped > 0) append(" ($skipped already on file, skipped.)")
                }
                LedgerImportResult(success = true, message = message, importedCount = fresh.size)
            }
        }
    }

    suspend fun latestBalanceCents(context: Context, accountId: String): Long? =
        CarDatabase.getDatabase(context).ledgerTransactionDao().latestBalanceCents(accountId)

    suspend fun allAccountIds(context: Context): List<String> =
        CarDatabase.getDatabase(context).ledgerTransactionDao().allAccountIds()

    suspend fun recentTransactions(context: Context, limit: Int = 20): List<LedgerTransaction> =
        CarDatabase.getDatabase(context).ledgerTransactionDao().getRecent(limit)

    private suspend fun isDuplicate(
        dao: com.kevin.legion.data.local.LedgerTransactionDao, txn: LedgerTransaction,
    ): Boolean = dao.countMatching(txn.accountId, txn.txnDate, txn.amountCents, txn.description) > 0

    /** Best-effort human-readable filename for the import confirmation message. */
    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: Exception) {
        null
    }
}

data class LedgerImportResult(val success: Boolean, val message: String, val importedCount: Int = 0)
