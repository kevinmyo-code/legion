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
 *
 * [subtotalCents]/[taxCents]/[otherChargesCents] are the three anchors
 * [PantryReceiptAgent]'s reconciliation gate itself read off the receipt's own print and verified
 * against ([receipt.totalCents] and the sum of [items]) - carried here (cutover 2,
 * `docs/architecture/cutover2-2026-08-24.md`) so [PantryController] can persist them alongside the
 * receipt and the gate's invariant stays re-checkable post-hoc, without re-running the extraction.
 * All three are nullable because a receipt may legitimately print no subtotal/tax/other-charges
 * line at all - null here means "not printed," never "not checked" (the gate itself already ran
 * either way; see [PantryReceiptAgent]'s own doc comment for the two-anchor arithmetic).
 */
sealed class PantryIngestResult {
    data class Success(
        val receipt: PantryReceipt,
        val items: List<PantryLineItem>,
        val subtotalCents: Long? = null,
        val taxCents: Long? = null,
        val otherChargesCents: Long? = null,
    ) : PantryIngestResult()
    data class Quarantined(val reason: String) : PantryIngestResult()
}
