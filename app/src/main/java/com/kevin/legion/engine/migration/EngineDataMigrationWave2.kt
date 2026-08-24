package com.kevin.legion.engine.migration

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.pantry.PantryAspectSeeder

/**
 * The one-time, idempotent copier that carries Wave 2's live data
 * (`.scratch/aspect-engine/issues/21-migration-waves.md` point 2) - every
 * [com.kevin.legion.data.local.PantryReceipt] and every [com.kevin.legion.data.local.PantryLineItem] -
 * onto the engine through [RecordStore], the engine's single write door. **Additive only**: reads
 * the legacy tables, writes new [com.kevin.legion.data.local.EngineRecord] rows; never touches,
 * drops, or mutates a legacy table. The old tables, `pantry/PantryController`, and every old
 * tool/screen keep working unchanged - cutover is a later, per-aspect wave (ticket 14 point 2),
 * not this one. Same shape as [EngineDataMigrationWave1] - see that object's own class doc for the
 * two-layer idempotency reasoning (completion flag + per-row `guid` backstop) this one reuses
 * rather than re-explaining.
 *
 * The exact field mapping, and the finding that there is NO quarantine state anywhere in the
 * legacy pantry schema to exclude (the reconciliation gate runs entirely upstream of Room -
 * `pantry/PantryReceiptAgent.kt`/`pantry/PantryController.kt` traced end to end), is
 * `docs/architecture/wave2-carve-2026-08-23.md`.
 *
 * **Provenance is unconditionally [RecordProvenance.LLM_RECONCILED]** for both record types - never
 * [RecordProvenance.USER] the way Wave 1's notes/places copy is, because every row this migration
 * will ever find already passed [com.kevin.legion.pantry.PantryReceiptAgent]'s reconciliation gate
 * before it ever reached Room (CLAUDE.md §5's v3 note: "every row is LLM-extracted by
 * construction").
 *
 * **`LineItem` rows are copied strictly after their own `Receipt`.** A `LineItem`'s `receipt`
 * field is a live [com.kevin.legion.data.local.FieldType.REFERENCE], and
 * [RecordStore.create]'s reference-existence check rejects any reference pointing at a record id
 * that does not yet exist - so [copyPantryIfNeeded] copies receipts first, keeps a
 * `legacy PantryReceipt.id -> new EngineRecord.id` map for the ones it touches THIS call, and
 * copies each receipt's line items immediately after (per-receipt, via
 * `PantryLineItemDao.getForReceipt`) rather than in one flat pass across every line item - which
 * also means a process death between a receipt and its own items can never leave an orphaned
 * reference: the next run's per-row `guid` check recognizes the receipt it already wrote (if any)
 * and resumes from there, and no line item is ever attempted before its own receipt's engine id is
 * known, in this run or any retry.
 */
object EngineDataMigrationWave2 {
    private const val PREFS = "engine_migration_wave2"
    private const val KEY_PANTRY_COMPLETED = "pantry_completed_v1"

    /** [receiptsCopied]/[lineItemsCopied] count only rows actually written this call - a row
     * skipped because its `guid` already existed (the per-row idempotency backstop) is not counted
     * twice across retries. [alreadyDone] is true only when the SharedPreferences fast path
     * skipped the whole domain without even querying the legacy tables. */
    data class Result(val receiptsCopied: Int, val lineItemsCopied: Int, val alreadyDone: Boolean)

    private fun store(db: CarDatabase) = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())

    suspend fun copyPantryIfNeeded(context: Context): Result {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PANTRY_COMPLETED, false)) {
            return Result(receiptsCopied = 0, lineItemsCopied = 0, alreadyDone = true)
        }

        val db = CarDatabase.getDatabase(context)
        val schema = PantryAspectSeeder.ensureSeeded(context)
        val recordStore = store(db)
        val receipts = db.pantryReceiptDao().getAll()

        var receiptsCopied = 0
        var lineItemsCopied = 0
        // Senior review MUST-FIX 2 (2026-08-23): a line-item WriteResult.Failure used to be
        // discarded, and the completion check below only ever re-derived receipt completeness -
        // a lost line item under an otherwise-successful receipt would set the flag anyway and
        // that row would never be retried again. Collected explicitly, folded into the
        // completion check at the end. The reconciliation-gate rule-6 posture ("a check that
        // passes when nothing parsed is not a gate") applied to migration completeness: a
        // completion flag satisfiable by a partial copy is the same shape of bug.
        val failedLineItemGuids = mutableSetOf<String>()

        for (receipt in receipts) {
            val receiptGuid = receipt.syncId
            val existingReceiptRecord = db.engineRecordDao().getByGuid(receiptGuid)

            val receiptEngineId: Long? = if (existingReceiptRecord != null) {
                existingReceiptRecord.id // already copied by an earlier, interrupted pass
            } else {
                val receiptFieldValues: Map<Long, Any?> = mapOf(
                    schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_STORE) to receipt.store,
                    schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_PURCHASE_DATE) to receipt.purchaseDate,
                    schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_CURRENCY) to receipt.currency.name,
                    schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_TOTAL) to receipt.totalCents,
                    schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_SOURCE_IMAGE_PATH) to receipt.sourceImagePath,
                )
                // Neither PantryReceipt nor PantryLineItem carries an insert-time clock of its own
                // (no createdAt/updatedAt column - see the carve doc's "correction, resolved during
                // implementation" note) - purchaseDate is the closest available anchor for "when
                // did this data become true," and is what both clocks are seeded from here.
                val result = recordStore.create(
                    recordTypeId = schema.receipt.recordTypeId,
                    fieldValues = receiptFieldValues,
                    provenance = RecordProvenance.LLM_RECONCILED,
                    now = receipt.purchaseDate,
                    guid = receiptGuid,
                )
                when (result) {
                    is RecordStore.WriteResult.Success -> {
                        receiptsCopied++
                        result.recordId
                    }
                    is RecordStore.WriteResult.Failure -> null // leave this receipt's items unattempted this pass
                }
            }

            if (receiptEngineId == null) continue

            for (item in db.pantryLineItemDao().getForReceipt(receipt.id)) {
                val itemGuid = item.syncId
                if (db.engineRecordDao().getByGuid(itemGuid) != null) continue // already copied

                val lineItemFieldValues: Map<Long, Any?> = mapOf(
                    schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_RECEIPT) to receiptEngineId,
                    schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_NAME) to item.name,
                    schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_QUANTITY) to item.quantity,
                    schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_UNIT_PRICE) to item.unitPriceCents,
                    schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_TOTAL_PRICE) to item.totalPriceCents,
                    schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_ESTIMATED_CALORIES_KCAL) to item.caloriesKcal,
                    schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_ESTIMATED_PROTEIN_G) to item.proteinG,
                    schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_ESTIMATED_CARBS_G) to item.carbsG,
                    schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_ESTIMATED_FAT_G) to item.fatG,
                )
                val result = recordStore.create(
                    recordTypeId = schema.lineItem.recordTypeId,
                    fieldValues = lineItemFieldValues,
                    provenance = RecordProvenance.LLM_RECONCILED,
                    now = receipt.purchaseDate,
                    guid = itemGuid,
                )
                if (result is RecordStore.WriteResult.Success) {
                    lineItemsCopied++
                } else {
                    failedLineItemGuids += itemGuid
                }
            }
        }

        // Only mark the whole domain complete if nothing was left unattempted this pass - a
        // receipt create Failure (schema mismatch, reference validation, etc.) means the domain is
        // NOT actually finished, and the flag must stay clear so the next app start retries.
        // MUST-FIX 2: a receipt Failure is re-derived here (its guid never landed), but a
        // LINE-ITEM Failure leaves its RECEIPT's guid very much present - re-deriving completeness
        // from `EngineRecordDao.getByGuid` alone would silently miss it, which is exactly the bug
        // this fixes. [failedLineItemGuids] is the direct record of what this call itself saw fail,
        // and is folded in explicitly rather than re-derived.
        val anyReceiptFailures = receipts.any { r -> db.engineRecordDao().getByGuid(r.syncId) == null }
        val anyFailures = anyReceiptFailures || failedLineItemGuids.isNotEmpty()
        if (!anyFailures) prefs.edit().putBoolean(KEY_PANTRY_COMPLETED, true).apply()

        return Result(receiptsCopied = receiptsCopied, lineItemsCopied = lineItemsCopied, alreadyDone = false)
    }

    /** App-start convenience, wrapped so a failure here can never cost anything else - same L12
     * "independent failure mode" reasoning [EngineDataMigrationWave1.runAll] already uses. */
    suspend fun runAll(context: Context) {
        runCatching { copyPantryIfNeeded(context) }
    }
}
