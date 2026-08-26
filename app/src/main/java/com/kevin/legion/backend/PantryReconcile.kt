package com.kevin.legion.backend

import android.content.Context
import androidx.room.withTransaction
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.PantryLineItem
import com.kevin.legion.data.local.PantryReceipt
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.pantry.PantryAspectSeeder
import com.kevin.legion.pantry.PantryReceiptAgent
import org.json.JSONObject

/**
 * The one-time (and re-runnable) Phase 4 step 1/2 job for Pantry
 * (`.scratch/backend-erp/issues/05-migration-path.md`, "Each aspect follows the identical
 * shape... 1. Upload... 2. Diff until clean") - the aspect-2-of-5 sibling of [PlacesReconcile],
 * same shape, one addition [PlacesReconcile] did not need (see [run]'s own doc comment).
 *
 * **Never touches, trashes, or deletes an engine record.** The engine stays the source of truth
 * until [Report.isClean] - deleting the engine's copy is a LATER phase (phase 6).
 */
object PantryReconcile {

    /**
     * @param engineCount how many active engine `Receipt` records this aspect had, BEFORE the
     *   local re-check below removes any of them from the upload set.
     * @param uploaded how many of the RECONCILING receipts were genuinely NEW server-side this run
     *   (a re-run reporting `0` is the expected, idempotent outcome per ticket 05 phase 4 step 1 -
     *   unlike [PlacesReconcile.Report.uploaded], which counts every successful upsert call because
     *   places are mutable, [PantryBackend.uploadMigratedReceipt] returns `false` for "already
     *   migrated", and that `false` does NOT increment this count). A failed upload still
     *   short-circuits into [Result.failure] rather than returning a report with a lower count.
     * @param skippedUnreconciled one entry per stored receipt whose figures no longer reconcile
     *   against [PantryReceiptAgent]'s own arithmetic when re-checked locally - CLAUDE.md section 4
     *   rule 2's "nothing partial is ever written" applied to a migration: such a receipt is
     *   reported here and never uploaded, never silently folded into [uploaded].
     * @param serverCountAfter the server's active receipt count after the upload.
     * @param replicaCountAfter the Room replica's active receipt count after being refreshed from
     *   the server's active set.
     * @param onlyOnEngine `records.guid`s the engine has (reconciling or not) that the server does
     *   not - non-empty after a clean run only for entries also present in [skippedUnreconciled]
     *   (a receipt that failed its local re-check is never uploaded, so it is expected to show up
     *   here rather than being hidden).
     * @param onlyOnServer `origin_guid`s the server has that the engine does not - a migrated row
     *   whose engine original has since vanished, or a stale engine read. Server rows created
     *   directly through [PantryBackend.commitReceipt] carry no `origin_guid` and are correctly
     *   excluded from this comparison entirely (see [PantryBackend.uploadMigratedReceipt]'s own doc
     *   comment).
     */
    data class Report(
        val engineCount: Int,
        val uploaded: Int,
        val skippedUnreconciled: List<String>,
        val serverCountAfter: Int,
        val replicaCountAfter: Int,
        val onlyOnEngine: List<String>,
        val onlyOnServer: List<String>,
    ) {
        /** True only when every reconciling engine receipt landed on the server and nothing is
         * left over on either side. A non-empty [skippedUnreconciled] always keeps this false -
         * a receipt this migration refused to trust is not a clean diff, it is a named exception. */
        val isClean: Boolean get() = onlyOnEngine.isEmpty() && onlyOnServer.isEmpty()
    }

    private data class EngineLineItem(
        val guid: String,
        val name: String,
        val quantity: Double,
        val unitPriceCents: Long?,
        val totalPriceCents: Long,
        val estimatedCaloriesKcal: Double?,
        val estimatedProteinG: Double?,
        val estimatedCarbsG: Double?,
        val estimatedFatG: Double?,
    )

    private data class EngineReceipt(
        val guid: String,
        val store: String,
        val purchaseDateEpochMs: Long,
        val currency: String,
        val totalCents: Long,
        val subtotalCents: Long?,
        val taxCents: Long?,
        val otherChargesCents: Long?,
        val items: List<EngineLineItem>,
    )

    /**
     * **The one addition [PlacesReconcile] did not need.** Every stored engine receipt is
     * re-checked against [PantryReceiptAgent.reconciliationFailure] - the SAME arithmetic the gate
     * ran at extraction time - BEFORE it is ever uploaded. A place has no arithmetic to go stale;
     * a receipt's stored total/subtotal/tax/items came from an LLM extraction that, however
     * unlikely, could have been hand-edited or corrupted since. Re-running the gate here turns this
     * migration into a verification pass rather than a bulk trust exercise (CLAUDE.md section 4
     * rule 2 is exactly why this is not optional). **Never a hard abort** - a receipt that fails
     * this re-check is reported in [Report.skippedUnreconciled] and the run continues, so one bad
     * receipt does not block the rest.
     */
    suspend fun run(context: Context, backend: PantryBackend): Result<Report> {
        val db = CarDatabase.getDatabase(context)
        val sch = PantryAspectSeeder.ensureSeeded(context)

        val receiptRecords = db.engineRecordDao().activeByRecordType(sch.receipt.recordTypeId)
        val lineItemRecords = db.engineRecordDao().activeByRecordType(sch.lineItem.recordTypeId)

        val itemsByReceiptEngineId = lineItemRecords.groupBy { record ->
            PayloadCodec.readReferenceId(JSONObject(record.payload), sch.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_RECEIPT))
        }

        val engineReceipts = receiptRecords.mapNotNull { record ->
            val payload = JSONObject(record.payload)
            fun s(name: String) = PayloadCodec.readString(payload, sch.receipt.fieldIds.getValue(name))
            fun l(name: String) = PayloadCodec.readLong(payload, sch.receipt.fieldIds.getValue(name))

            val store = s(PantryAspectSeeder.FIELD_STORE) ?: return@mapNotNull null
            val currency = s(PantryAspectSeeder.FIELD_CURRENCY) ?: return@mapNotNull null
            val total = l(PantryAspectSeeder.FIELD_TOTAL) ?: return@mapNotNull null

            val items = (itemsByReceiptEngineId[record.id] ?: emptyList()).map { itemRecord ->
                val itemPayload = JSONObject(itemRecord.payload)
                fun si(name: String) = PayloadCodec.readString(itemPayload, sch.lineItem.fieldIds.getValue(name))
                fun li(name: String) = PayloadCodec.readLong(itemPayload, sch.lineItem.fieldIds.getValue(name))
                fun di(name: String) = PayloadCodec.readDouble(itemPayload, sch.lineItem.fieldIds.getValue(name))
                EngineLineItem(
                    guid = itemRecord.guid,
                    name = si(PantryAspectSeeder.FIELD_NAME).orEmpty(),
                    quantity = di(PantryAspectSeeder.FIELD_QUANTITY) ?: 1.0,
                    unitPriceCents = li(PantryAspectSeeder.FIELD_UNIT_PRICE),
                    totalPriceCents = li(PantryAspectSeeder.FIELD_TOTAL_PRICE) ?: 0L,
                    estimatedCaloriesKcal = di(PantryAspectSeeder.FIELD_ESTIMATED_CALORIES_KCAL),
                    estimatedProteinG = di(PantryAspectSeeder.FIELD_ESTIMATED_PROTEIN_G),
                    estimatedCarbsG = di(PantryAspectSeeder.FIELD_ESTIMATED_CARBS_G),
                    estimatedFatG = di(PantryAspectSeeder.FIELD_ESTIMATED_FAT_G),
                )
            }

            EngineReceipt(
                guid = record.guid,
                store = store,
                purchaseDateEpochMs = l(PantryAspectSeeder.FIELD_PURCHASE_DATE) ?: record.createdAt,
                currency = currency,
                totalCents = total,
                subtotalCents = l(PantryAspectSeeder.FIELD_SUBTOTAL),
                taxCents = l(PantryAspectSeeder.FIELD_TAX),
                otherChargesCents = l(PantryAspectSeeder.FIELD_OTHER_CHARGES),
                items = items,
            )
        }

        // The owed re-check, ahead of any network call - see this function's own doc comment.
        val reconciling = mutableListOf<EngineReceipt>()
        val skipped = mutableListOf<String>()
        for (receipt in engineReceipts) {
            val currency = LedgerCurrency.entries.firstOrNull { it.name == receipt.currency } ?: LedgerCurrency.USD
            val itemsTotal = receipt.items.sumOf { it.totalPriceCents }
            val failure = PantryReceiptAgent.reconciliationFailure(
                itemCount = receipt.items.size,
                itemsTotalCents = itemsTotal,
                subtotalCents = receipt.subtotalCents,
                taxCents = receipt.taxCents,
                otherChargesCents = receipt.otherChargesCents,
                totalCents = receipt.totalCents,
                currency = currency,
            )
            if (failure != null) {
                skipped.add("${receipt.store} (${receipt.guid}): $failure")
            } else {
                reconciling.add(receipt)
            }
        }

        var uploaded = 0
        for (receipt in reconciling) {
            val migrated = MigratedReceipt(
                originGuid = receipt.guid,
                store = receipt.store,
                purchaseDateEpochMs = receipt.purchaseDateEpochMs,
                currency = receipt.currency,
                totalCents = receipt.totalCents,
                subtotalCents = receipt.subtotalCents,
                taxCents = receipt.taxCents,
                otherChargesCents = receipt.otherChargesCents,
                lines = receipt.items.map { item ->
                    MigratedReceiptLine(
                        originGuid = item.guid,
                        name = item.name,
                        quantity = item.quantity,
                        unitPriceCents = item.unitPriceCents,
                        totalPriceCents = item.totalPriceCents,
                        estimatedCaloriesKcal = item.estimatedCaloriesKcal,
                        estimatedProteinG = item.estimatedProteinG,
                        estimatedCarbsG = item.estimatedCarbsG,
                        estimatedFatG = item.estimatedFatG,
                    )
                },
            )
            // Unlike PlacesReconcile's upsert (always a meaningful write, mutable rows), a `false`
            // here means "already migrated on a previous run" - a real no-op, not a fresh upload,
            // so it must not inflate [uploaded] on a re-run (see Report.uploaded's own doc comment).
            val wasNewUpload = backend.uploadMigratedReceipt(migrated)
                .getOrElse { return Result.failure(it) }
            if (wasNewUpload) uploaded++
        }

        val serverReceipts = backend.fetchActiveReceipts().getOrElse { return Result.failure(it) }

        // Full clear-and-refill, not a per-row upsert - see PantryReceiptDao/PantryLineItemDao's
        // deleteAllForReplicaRefresh doc comments for why there is no natural key to upsert against.
        db.withTransaction {
            db.pantryReceiptDao().deleteAllForReplicaRefresh()
            db.pantryLineItemDao().deleteAllForReplicaRefresh()
            for (serverReceipt in serverReceipts) {
                val currency = LedgerCurrency.entries.firstOrNull { it.name == serverReceipt.currency } ?: LedgerCurrency.USD
                val localReceiptId = db.pantryReceiptDao().insert(
                    PantryReceipt(
                        store = serverReceipt.store,
                        purchaseDate = serverReceipt.purchaseDateEpochMs,
                        currency = currency,
                        totalCents = serverReceipt.totalCents,
                        sourceImagePath = "",
                        syncId = serverReceipt.serverId,
                    ),
                )
                db.pantryLineItemDao().insertAll(
                    serverReceipt.lines.map { line ->
                        PantryLineItem(
                            receiptId = localReceiptId,
                            name = line.name,
                            quantity = line.quantity,
                            unitPriceCents = line.unitPriceCents,
                            totalPriceCents = line.totalPriceCents,
                            caloriesKcal = line.estimatedCaloriesKcal?.toInt(),
                            proteinG = line.estimatedProteinG,
                            carbsG = line.estimatedCarbsG,
                            fatG = line.estimatedFatG,
                        )
                    },
                )
            }
        }

        val engineGuids = engineReceipts.map { it.guid }.toSet()
        val serverGuids = serverReceipts.mapNotNull { it.originGuid }.toSet()

        return Result.success(
            Report(
                engineCount = engineReceipts.size,
                uploaded = uploaded,
                skippedUnreconciled = skipped,
                serverCountAfter = serverReceipts.size,
                replicaCountAfter = db.pantryReceiptDao().getAll().size,
                onlyOnEngine = (engineGuids - serverGuids).sorted(),
                onlyOnServer = (serverGuids - engineGuids).sorted(),
            ),
        )
    }
}
