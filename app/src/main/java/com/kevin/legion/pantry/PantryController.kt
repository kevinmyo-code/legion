package com.kevin.legion.pantry

import android.content.Context
import android.util.Log
import com.kevin.legion.data.PantryPhotoStore
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.PantryLineItem
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

    suspend fun totalSpendCents(context: Context): Long =
        CarDatabase.getDatabase(context).pantryReceiptDao().totalSpendCents()
}

data class PantryImportResult(val success: Boolean, val message: String, val itemCount: Int = 0)
