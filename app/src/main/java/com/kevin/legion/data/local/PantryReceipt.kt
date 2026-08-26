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
 *
 * **AMENDED 2026-08-26 (CLAUDE.md section 4 rule 7's amendment, ticket 08).** [unaccountedCents]
 * mirrors `receipts.unaccounted_cents` (`supabase/migrations/20260826000300_receipt_unaccounted.sql`)
 * - null on every ordinary receipt. Non-null means this receipt charged more than its captured
 * lines explain and could never be re-verified (the source photo is gone); it is what the old
 * class comment above still calls "LLM-extracted by construction", now split into two provenance
 * shapes rather than one. **Never fed back into [PantryReceiptAgent]'s reconciliation arithmetic**
 * - it is the residual the gate could not explain, not a new anchor. [provenance] carries the
 * server's own value (`"LLM_RECONCILED"` or `"UNRECONCILED"`) so a UI surface never has to
 * re-derive it from nullability alone. Every surface rendering a receipt with a non-null
 * [unaccountedCents] must say so in words (rule 7 condition 3) - see
 * `ui/pantry/PantryReceiptSection.kt`.
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
    val provenance: String = "LLM_RECONCILED",
    val unaccountedCents: Long? = null,
)
