package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One grocery receipt, ingested from a photo (`.claude/plans/wiggly-beaming-
 * quasar.md`). Unlike [LedgerTransaction], there's no `ingestMethod` field:
 * every row here is LLM-extracted by construction (no deterministic layout
 * exists for a photographed receipt the way bank statements have), so the
 * field would always read the same value - this is that, stated once here
 * rather than a column nobody reads.
 *
 * [totalCents] is the receipt's OWN printed total - the reconciliation gate
 * ([com.kevin.legion.pantry.PantryReceiptAgent]) requires the sum of this
 * receipt's [PantryLineItem]s to equal it exactly before anything is written.
 * `Long` cents, not `Double`, for the same exactness reason as
 * [LedgerTransaction.amountCents].
 */
@Entity(tableName = "pantry_receipts")
data class PantryReceipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val store: String,
    val purchaseDate: Long, // epoch millis
    val currency: LedgerCurrency,
    val totalCents: Long,
    // May point to a file PantryPhotoStore has since deleted (success case) -
    // kept as a text record of provenance, not a live reference.
    val sourceImagePath: String,
    val syncId: String = java.util.UUID.randomUUID().toString(),
)
