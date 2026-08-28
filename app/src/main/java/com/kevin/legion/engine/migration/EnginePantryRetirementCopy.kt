package com.kevin.legion.engine.migration

import android.content.Context
import androidx.room.withTransaction
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.PantryLineItem
import com.kevin.legion.data.local.PantryReceipt
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.pantry.PantryAspectSeeder
import org.json.JSONObject

/**
 * Step 2 of the engine retirement sequence (`.scratch/backend-erp/issues/15-engine-retirement-sequence.md`):
 * the one-time, idempotent copier that reconciles the engine's `Receipt`/`LineItem` records onto
 * the legacy `pantry_receipts`/`pantry_line_items` tables BEFORE [com.kevin.legion.pantry.PantryController]'s
 * unconfigured read/write path is repointed off [com.kevin.legion.engine.RecordStore] and onto
 * [com.kevin.legion.data.local.PantryReceiptDao]/[com.kevin.legion.data.local.PantryLineItemDao].
 * Mirrors [EnginePlacesRetirementCopy]'s shape exactly - see that object's own class doc for the
 * general reasoning ("runs the opposite direction from the wave copiers", "deletes nothing"),
 * repeated here only where pantry's answer differs.
 *
 * **Identity is by [com.kevin.legion.data.local.EngineRecord.guid], and here it actually works -
 * unlike [EnginePlacesRetirementCopy], which had to fall back to the label.** Places broke on guid
 * because wave 1's forward copy minted a DETERMINISTIC guid from the label while
 * [com.kevin.legion.engine.RecordStore.create] defaults to a random one, so the two could not be
 * matched. Pantry never has that split: [EngineDataMigrationWave2] keyed its forward copy on
 * `receipt.syncId`/`item.syncId` **verbatim** (`guid = receiptGuid` / `guid = itemGuid` in that
 * object), so a forward-copied engine record's guid IS the legacy row's `syncId`, letter for
 * letter. And every row written the OTHER way -
 * [com.kevin.legion.pantry.PantryController.writeReceipt], the unconfigured import path - passes
 * `guid = result.receipt.syncId` / `guid = item.syncId` too, where [PantryReceipt.syncId] /
 * [PantryLineItem.syncId] mint their own random UUID at construction and that mint happens exactly
 * once, in [com.kevin.legion.pantry.PantryReceiptAgent]'s result object, before the engine write.
 * So an engine Receipt/LineItem's guid is always, in both directions, the SAME string its legacy
 * counterpart would carry as `syncId` - there is no second identity to reconcile against a first.
 * That is also why `supabase/migrations/20260826000100_origin_guid.sql`'s header says receipts
 * have "no natural key even in principle": the guid is not a semantic key derived from the data
 * (store name, date, total) the way `places.label` is - it is simply the row's own identity,
 * carried faithfully through every copy this codebase has ever done to it. Traced through both
 * [EngineDataMigrationWave2] and [com.kevin.legion.pantry.PantryController.writeReceipt], not
 * assumed from the SQL comment alone.
 *
 * **Reconcile-and-repoint, never blind-switch (ticket 05's rule): this only ever fills gaps.** A
 * `syncId` already present in `pantry_receipts` (forward-copied by [EngineDataMigrationWave2], or
 * previously repointed by an earlier, interrupted pass of this very copier) is left alone -
 * `pantry_receipts` wins ties, exactly as `places` does in [EnginePlacesRetirementCopy]. There is
 * no tombstone concept in this table to protect against resurrecting (see
 * `docs/architecture/wave2-carve-2026-08-23.md`'s finding that pantry has none), so unlike places
 * the "already present" check alone is the whole guard.
 *
 * **Provenance and `unaccountedCents` are not carried through the payload - they are asserted.**
 * Every engine `Receipt` is unconditionally [RecordProvenance.LLM_RECONCILED]
 * ([com.kevin.legion.pantry.PantryController.writeReceipt] hardcodes it, and there is no other
 * engine write path for this record type), and [PantryAspectSeeder] has no field at all for
 * `unaccountedCents` - CLAUDE.md section 4 rule 7's `UNRECONCILED`/`unaccountedCents` state exists
 * only on pre-engine legacy rows that predate this table having a `records.guid` to be reached
 * from at all. Those rows are never at risk here: their `syncId` is already present in
 * `pantry_receipts` (they were never copied INTO the engine with a mismatched provenance either -
 * [EngineDataMigrationWave2] runs strictly forward, legacy to engine, and this copier runs strictly
 * the other way), so the "already present" skip above protects them before this function ever
 * reads their fields. A row this copier actually writes is, by construction, gate-verified in full
 * - writing anything but `LLM_RECONCILED`/`null` here would be the "row that arrives looking
 * verified when it was not" failure CLAUDE.md section 4 rule 7 exists to prevent, just approached
 * from the opposite direction (inventing a WEAKER provenance than the truth is exactly as wrong as
 * inventing a stronger one).
 *
 * **The subtotal/tax/otherCharges anchors are carried through, not asserted (v44, the
 * coordinator-authorised follow-up to this ticket).** Unlike provenance/`unaccountedCents` above,
 * these three fields have a real, per-receipt value on the engine record - `PantryReceiptAgent`'s
 * gate read them off THIS receipt's own print. Asserting a fixed value would be wrong here in the
 * exact opposite way asserting a fixed provenance would be right: dropping them (writing `null`
 * regardless of what the engine held) would silently discard the gate's own inputs for a receipt
 * that already HAD them, which is precisely the "new ingestion path" CLAUDE.md section 4 rule 7's
 * 2026-08-26 amendment (ticket 08) refuses to license - that amendment covers rows that already
 * lost their anchors, never a path that keeps producing more of them. So these three are read from
 * the SAME engine payload as every other field and copied verbatim, `null` only when the engine
 * record's own field is `null` (the receipt printed no subtotal/tax line to begin with).
 *
 * **A receipt lands with all of its line items or none of them.** Ticket 15's brief: "a receipt
 * without its line items, or lines without their header, is worse than neither." Each receipt (and
 * whichever of its line items still need copying) is written inside one
 * [androidx.room.withTransaction] block - the same discipline
 * [com.kevin.legion.pantry.PantryController.writeReceipt] already uses for the forward write, for
 * the identical reason.
 *
 * **Deletes nothing.** The engine's Receipt/LineItem records are read here and never trashed,
 * updated, or touched - ticket 15 is explicit that nothing is deleted until every aspect is
 * repointed and soaked.
 */
object EnginePantryRetirementCopy {
    private const val PREFS = "engine_pantry_retirement"
    private const val KEY_COMPLETED = "pantry_repointed_v1"

    /** [receiptsCopied]/[lineItemsCopied] count only rows actually written this call. [alreadyDone]
     * is true only when the SharedPreferences fast path skipped the pass entirely without even
     * reading the engine. */
    data class Result(val receiptsCopied: Int, val lineItemsCopied: Int, val alreadyDone: Boolean)

    /**
     * Copies every active engine `Receipt` (and its still-missing `LineItem`s) whose guid has no
     * row at all in `pantry_receipts`/`pantry_line_items` into those tables. Idempotent two ways,
     * matching [EnginePlacesRetirementCopy]'s own shape: the [KEY_COMPLETED] flag short-circuits
     * every call after the first successful pass, and even without it a re-run is safe because the
     * per-guid existence check simply finds nothing left to copy the second time.
     */
    suspend fun copyIfNeeded(context: Context): Result {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_COMPLETED, false)) return Result(receiptsCopied = 0, lineItemsCopied = 0, alreadyDone = true)

        val db = CarDatabase.getDatabase(context)
        val schema = PantryAspectSeeder.ensureSeeded(context)

        // Every syncId `pantry_receipts`/`pantry_line_items` has ever seen - see this object's
        // class doc for why an existing syncId, not a semantic field, is the whole guard here.
        val existingReceiptSyncIds = db.pantryReceiptDao().getAllSyncIds().toHashSet()
        val existingLineItemSyncIds = db.pantryLineItemDao().getAllSyncIds().toHashSet()

        val engineReceipts = db.engineRecordDao().activeByRecordType(schema.receipt.recordTypeId)
        val engineLineItems = db.engineRecordDao().activeByRecordType(schema.lineItem.recordTypeId)

        var receiptsCopied = 0
        var lineItemsCopied = 0

        for (receiptRecord in engineReceipts) {
            if (receiptRecord.guid in existingReceiptSyncIds) continue // `pantry_receipts` wins ties - reconcile, never overwrite

            val payload = JSONObject(receiptRecord.payload)
            fun readS(name: String) = PayloadCodec.readString(payload, schema.receipt.fieldIds.getValue(name))
            fun readL(name: String) = PayloadCodec.readLong(payload, schema.receipt.fieldIds.getValue(name))
            val currency = LedgerCurrency.entries.firstOrNull { it.name == readS(PantryAspectSeeder.FIELD_CURRENCY) }
                ?: LedgerCurrency.USD

            // This receipt's still-missing line items - matched by the REFERENCE field's stored
            // record id, same read [com.kevin.legion.pantry.PantryController.toLineItem] uses.
            val itemsToCopy = engineLineItems.filter { item ->
                val itemPayload = JSONObject(item.payload)
                val referencedReceiptId = PayloadCodec.readReferenceId(
                    itemPayload,
                    schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_RECEIPT),
                )
                referencedReceiptId == receiptRecord.id && item.guid !in existingLineItemSyncIds
            }

            // Header and its lines land together or not at all - see this object's class doc.
            db.withTransaction {
                val newReceiptId = db.pantryReceiptDao().insert(
                    PantryReceipt(
                        store = readS(PantryAspectSeeder.FIELD_STORE).orEmpty(),
                        purchaseDate = readL(PantryAspectSeeder.FIELD_PURCHASE_DATE) ?: receiptRecord.createdAt,
                        currency = currency,
                        totalCents = readL(PantryAspectSeeder.FIELD_TOTAL) ?: 0L,
                        sourceImagePath = readS(PantryAspectSeeder.FIELD_SOURCE_IMAGE_PATH).orEmpty(),
                        syncId = receiptRecord.guid,
                        // Asserted, not carried - see this object's class doc's provenance section.
                        provenance = RecordProvenance.LLM_RECONCILED.name,
                        unaccountedCents = null,
                        // v44 (coordinator-authorised follow-up): carried through exactly, not
                        // asserted - unlike provenance/unaccountedCents above, the engine's own
                        // three anchor fields have a real, receipt-specific value to preserve, and
                        // dropping them here would recreate ticket 08's defect for every receipt
                        // this copier moves. null when the engine record itself has none (the
                        // receipt printed no subtotal/tax line), never a fabricated zero.
                        subtotalCents = readL(PantryAspectSeeder.FIELD_SUBTOTAL),
                        taxCents = readL(PantryAspectSeeder.FIELD_TAX),
                        otherChargesCents = readL(PantryAspectSeeder.FIELD_OTHER_CHARGES),
                    ),
                )

                for (itemRecord in itemsToCopy) {
                    val itemPayload = JSONObject(itemRecord.payload)
                    fun readItemS(name: String) = PayloadCodec.readString(itemPayload, schema.lineItem.fieldIds.getValue(name))
                    fun readItemL(name: String) = PayloadCodec.readLong(itemPayload, schema.lineItem.fieldIds.getValue(name))
                    fun readItemD(name: String) = PayloadCodec.readDouble(itemPayload, schema.lineItem.fieldIds.getValue(name))

                    db.pantryLineItemDao().insertAll(
                        listOf(
                            PantryLineItem(
                                receiptId = newReceiptId,
                                name = readItemS(PantryAspectSeeder.FIELD_NAME).orEmpty(),
                                quantity = readItemD(PantryAspectSeeder.FIELD_QUANTITY) ?: 1.0,
                                unitPriceCents = readItemL(PantryAspectSeeder.FIELD_UNIT_PRICE),
                                totalPriceCents = readItemL(PantryAspectSeeder.FIELD_TOTAL_PRICE) ?: 0L,
                                caloriesKcal = readItemD(PantryAspectSeeder.FIELD_ESTIMATED_CALORIES_KCAL)?.toInt(),
                                proteinG = readItemD(PantryAspectSeeder.FIELD_ESTIMATED_PROTEIN_G),
                                carbsG = readItemD(PantryAspectSeeder.FIELD_ESTIMATED_CARBS_G),
                                fatG = readItemD(PantryAspectSeeder.FIELD_ESTIMATED_FAT_G),
                                syncId = itemRecord.guid,
                            ),
                        ),
                    )
                    lineItemsCopied++
                }
            }

            existingReceiptSyncIds += receiptRecord.guid // guards two engine records that somehow share a guid within one pass
            receiptsCopied++
        }

        prefs.edit().putBoolean(KEY_COMPLETED, true).apply()
        return Result(receiptsCopied = receiptsCopied, lineItemsCopied = lineItemsCopied, alreadyDone = false)
    }
}
