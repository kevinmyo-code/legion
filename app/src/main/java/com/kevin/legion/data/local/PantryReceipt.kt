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
 *
 * **v43 -> v44 (engine retirement step 2, `.scratch/backend-erp/issues/15-engine-retirement-sequence.md`).**
 * [subtotalCents]/[taxCents]/[otherChargesCents] mirror the three ENGINE-only fields cutover 2 added
 * to [com.kevin.legion.engine.pantry.PantryAspectSeeder] (`FIELD_SUBTOTAL`/`FIELD_TAX`/
 * `FIELD_OTHER_CHARGES`, "the gate invariant is re-checkable post-hoc") but that this entity never
 * carried - repointing [com.kevin.legion.pantry.PantryController.writeReceipt] onto this table
 * without them would have silently discarded the reconciliation gate's own inputs for every
 * receipt an unconfigured install writes from now on, which is precisely the "new ingestion path"
 * CLAUDE.md section 4 rule 7's 2026-08-26 amendment (ticket 08) refuses to license - that amendment
 * covers rows already stored with no anchors, never a path that keeps producing more of them. All
 * three are nullable `MONEY_CENTS`-shaped `Long` for the identical reason the engine fields are
 * optional: a receipt legitimately prints no subtotal/tax line (the tax-inclusive-basket branch in
 * [com.kevin.legion.pantry.PantryReceiptAgent]'s own reconciliation), and a pre-v44 row genuinely
 * has none at all (null here means "not printed" or "predates this column," never "not checked" -
 * the gate itself already ran either way).
 *
 * **v45 -> v46 (ticket 09, `.scratch/backend-erp/issues/09-backups-do-not-cover-files.md`).**
 * [photoObjectPath] mirrors `receipts.photo_object_path` server-side - null on every unconfigured
 * install (there is no bucket to upload to) and on any configured receipt whose upload to
 * [com.kevin.legion.backend.SupabasePhotoBackend] failed (see
 * [com.kevin.legion.pantry.PantryController.commitReceiptRemote]'s own doc comment: a failed photo
 * upload never blocks or rolls back the receipt commit). Non-null means the photo bytes have a
 * durable copy in the household's Storage bucket even after [sourceImagePath]'s local staging file
 * is gone - which, per that field's own comment, is true of EVERY successfully-committed receipt
 * by design, not just ones affected by the ticket 09 incident. [PhotoFieldResolver] reads this
 * (via a caller-supplied `hasRemoteCopy` bit) to tell "gone and unrecoverable" apart from "not on
 * this device, but safely backed up" - collapsing those two would be exactly the "unreadable and
 * empty are different sentences" mistake this ticket exists to fix, aimed at a photo instead of a
 * permission.
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
    // v44 (engine retirement step 2) - see this class's own doc comment above.
    val subtotalCents: Long? = null,
    val taxCents: Long? = null,
    val otherChargesCents: Long? = null,
    // v46 (ticket 09) - see this class's own doc comment above.
    val photoObjectPath: String? = null,
)
