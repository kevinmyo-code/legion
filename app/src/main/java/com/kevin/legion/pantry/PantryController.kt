package com.kevin.legion.pantry

import android.content.Context
import android.util.Log
import com.kevin.legion.data.PantryPhotoStore
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.PantryCurrencyTotal
import com.kevin.legion.data.local.PantryLineItem
import com.kevin.legion.data.local.PantryLineItemWithCurrency
import com.kevin.legion.data.local.PantryReceipt
import com.kevin.legion.data.local.PantryReceiptSummary
import java.io.File

/**
 * Orchestrates receipt-photo ingestion - mirrors
 * [com.kevin.legion.ledger.LedgerController]'s shape.
 * `.claude/plans/wiggly-beaming-quasar.md`.
 */
object PantryController {
    private const val TAG = "PantryController"

    /**
     * Reads [imageFile] (already saved via [PantryPhotoStore]), extracts it
     * through [PantryReceiptAgent], and - only on a fully-reconciled result -
     * inserts the receipt then its line items (stamped with the new receipt's
     * id), deleting the source photo. On a quarantine, the photo is kept so
     * the driver can inspect or retry without re-taking it.
     */
    suspend fun importReceipt(context: Context, imageFile: File): PantryImportResult {
        val bytes = try {
            imageFile.readBytes()
        } catch (e: Exception) {
            Log.w(TAG, "failed to read ${imageFile.path}: ${e.message}")
            return PantryImportResult(success = false, message = "I couldn't read that photo - try again.")
        }

        return when (val result = PantryReceiptAgent.extract(bytes, imageFile.path)) {
            is PantryIngestResult.Quarantined -> PantryImportResult(success = false, message = result.reason)
            is PantryIngestResult.Success -> {
                val db = CarDatabase.getDatabase(context)
                val receiptId = db.pantryReceiptDao().insert(result.receipt)
                db.pantryLineItemDao().insertAll(result.items.map { it.copy(receiptId = receiptId) })
                PantryPhotoStore.delete(context, imageFile)
                PantryImportResult(
                    success = true,
                    message = "Logged ${result.items.size} item(s) from ${result.receipt.store}.",
                    itemCount = result.items.size,
                )
            }
        }
    }

    suspend fun recentLineItems(context: Context, limit: Int = 20): List<PantryLineItem> =
        CarDatabase.getDatabase(context).pantryLineItemDao().getRecent(limit)

    /** [recentLineItems], each tagged with its own receipt's currency - see [PantryLineItemWithCurrency]'s doc comment. */
    suspend fun recentLineItemsWithCurrency(context: Context, limit: Int = 20): List<PantryLineItemWithCurrency> =
        CarDatabase.getDatabase(context).pantryLineItemDao().getRecentWithCurrency(limit)

    suspend fun totalSpendCents(context: Context): Long =
        CarDatabase.getDatabase(context).pantryReceiptDao().totalSpendCents()

    /** Total grocery spend PER currency, never combined - see [com.kevin.legion.data.local.PantryReceiptDao.totalSpendCentsByCurrency]'s doc comment. */
    suspend fun totalSpendCentsByCurrency(context: Context): List<PantryCurrencyTotal> =
        CarDatabase.getDatabase(context).pantryReceiptDao().totalSpendCentsByCurrency()

    /**
     * The [limitReceipts] most recent receipts, each paired with its own line
     * items - what ticket 09's pantry screen (resolution §2, TREATMENT B
     * SEGREGATED) groups by, one receipt per `ON THE RECEIPT` / `ESTIMATED,
     * NOT ON THE RECEIPT` pair. Composed from [PantryReceiptDao.getRecent] +
     * [PantryLineItemDao.getForReceipt] rather than a new joined DAO query -
     * receipt counts are small (personal grocery volume, not a ledger), so
     * N+1 here costs nothing and avoids widening the DAO surface for a read
     * only this screen needs.
     */
    suspend fun recentReceiptsWithItems(context: Context, limitReceipts: Int = 10): List<Pair<PantryReceipt, List<PantryLineItem>>> {
        val db = CarDatabase.getDatabase(context)
        val receipts = db.pantryReceiptDao().getRecent(limitReceipts)
        return receipts.map { receipt -> receipt to db.pantryLineItemDao().getForReceipt(receipt.id) }
    }

    /**
     * Every receipt's date/total/currency (quant-viz ticket 07) - the SPEND panel's monthly bars
     * need the driver's whole ingestion history, not [recentReceiptsWithItems]'s capped list. See
     * [com.kevin.legion.data.local.PantryReceiptDao.getAllForCharts]'s doc comment for why this is
     * a separate, lighter read rather than a widened `recentReceiptsWithItems`.
     */
    suspend fun allReceiptSummaries(context: Context): List<PantryReceiptSummary> =
        CarDatabase.getDatabase(context).pantryReceiptDao().getAllForCharts()
}

data class PantryImportResult(val success: Boolean, val message: String, val itemCount: Int = 0)
