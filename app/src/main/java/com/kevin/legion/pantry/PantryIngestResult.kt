package com.kevin.legion.pantry

import com.kevin.legion.data.local.PantryLineItem
import com.kevin.legion.data.local.PantryReceipt

/**
 * Outcome of ingesting one receipt photo. Mirrors
 * [com.kevin.legion.ledger.LedgerIngestResult] exactly - [Quarantined] is not
 * exceptional, it's the expected outcome for a blurry photo, a receipt the
 * model misread, or one whose printed total the extracted lines don't sum to.
 * Nothing is written to the pantry tables on a quarantine.
 *
 * [receipt] in [Success] carries `id = 0` (not yet inserted) - the caller
 * ([PantryController]) inserts it, then stamps the returned row id onto each
 * [PantryLineItem.receiptId] before inserting those.
 */
sealed class PantryIngestResult {
    data class Success(val receipt: PantryReceipt, val items: List<PantryLineItem>) : PantryIngestResult()
    data class Quarantined(val reason: String) : PantryIngestResult()
}
