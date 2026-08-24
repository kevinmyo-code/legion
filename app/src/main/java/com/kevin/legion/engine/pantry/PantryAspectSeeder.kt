package com.kevin.legion.engine.pantry

import android.content.Context
import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.DeletePolicy
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordType
import com.kevin.legion.engine.FieldConfig

/**
 * The built-in Pantry aspect - Wave 2 of `.scratch/aspect-engine/issues/21-migration-waves.md`
 * ("notes/lists/places, then pantry, then ledger, then fleet"). Follows
 * [com.kevin.legion.engine.notes.NotesAspectSeeder]'s exact shape (idempotent at every
 * granularity, `ownerPluginId`/`locked` per ticket 11 answer point 1) - see that object's own doc
 * comment for the reasoning this one does not repeat.
 *
 * **The carve, in full, is `docs/architecture/wave2-carve-2026-08-23.md`.** Its headline finding:
 * unlike Wave 1's notes/places, there is no quarantine state anywhere in the legacy pantry schema
 * to worry about - [com.kevin.legion.pantry.PantryReceiptAgent]'s reconciliation gate runs entirely
 * BEFORE a row ever reaches Room, so every [com.kevin.legion.data.local.PantryReceipt]/
 * [com.kevin.legion.data.local.PantryLineItem] this seeder's schema will ever hold is, by
 * construction, gate-reconciled - provenance for a migrated row is unconditionally
 * [com.kevin.legion.data.local.RecordProvenance.LLM_RECONCILED] (see
 * [com.kevin.legion.engine.migration.EngineDataMigrationWave2], never this seeder - a seeder only
 * ever declares SCHEMA, never writes a record).
 *
 * Two record types share this one aspect: [ensureSeeded] returns both schemas so the migration
 * copier (which must create every `Receipt` before any `LineItem` that references it - see that
 * copier's own doc comment) has both available from a single call.
 */
object PantryAspectSeeder {
    const val ASPECT_NAME = "Pantry"
    const val OWNER_PLUGIN_ID = "pantry"

    const val RECEIPT_RECORD_TYPE_NAME = "Receipt"
    const val LINE_ITEM_RECORD_TYPE_NAME = "LineItem"

    // ---- Receipt fields -------------------------------------------------------------------------
    const val FIELD_STORE = "store"
    const val FIELD_PURCHASE_DATE = "purchaseDate"
    const val FIELD_CURRENCY = "currency"
    const val FIELD_TOTAL = "total"
    const val FIELD_SOURCE_IMAGE_PATH = "sourceImagePath"

    /** Cutover 2 (`docs/architecture/cutover2-2026-08-24.md`), the wave-2-carve-owed follow-up
     * (`.scratch/aspect-engine/issues/22-cutover-per-aspect.md` open question 2: "persist
     * subtotal/tax/other so the gate invariant is re-checkable post-hoc"). [PantryReceiptAgent]
     * already reads these three off the receipt's own print and reconciles against them
     * (`sum(items) == subtotal`, `subtotal + tax + otherCharges == total`) - before this addition
     * the gate's WORK was verified but its INPUTS were thrown away the moment the receipt was
     * written, so nothing later could re-derive "did this actually tie out" without re-running the
     * whole extraction. All three are optional MONEY_CENTS - not required/locked, since a receipt
     * legitimately prints no subtotal/tax line (the tax-free-basket branch in
     * [PantryReceiptAgent]'s own reconciliation) and a migrated pre-cutover row has none at all
     * (the legacy [com.kevin.legion.data.local.PantryReceipt] entity never carried these columns -
     * see [com.kevin.legion.engine.migration.EngineDataMigrationWave2]'s own doc comment: historical
     * rows are correctly anchor-less, never backfilled with a fabricated figure). Added via the
     * SAME idempotent [ensureField] path every other field in this seeder uses - additive schema
     * DATA, not a Room migration, reaching Kevin's already-seeded phone the next time this function
     * runs. */
    const val FIELD_SUBTOTAL = "subtotal"
    const val FIELD_TAX = "tax"
    const val FIELD_OTHER_CHARGES = "otherCharges"

    /** [com.kevin.legion.data.local.LedgerCurrency]'s names, duplicated here as plain strings
     * rather than a dependency on that enum - same "engine package never depends on a plugin
     * package" reasoning [com.kevin.legion.engine.notes.NotesAspectSeeder.REPEAT_KIND_OPTIONS]'s
     * own doc comment states for `notes/RepeatKind`. */
    val CURRENCY_OPTIONS = listOf("SGD", "USD")

    // ---- LineItem fields --------------------------------------------------------------------------
    const val FIELD_RECEIPT = "receipt"
    const val FIELD_NAME = "name"
    const val FIELD_QUANTITY = "quantity"
    const val FIELD_UNIT_PRICE = "unitPrice"
    const val FIELD_TOTAL_PRICE = "totalPrice"

    /** ESTIMATE fields - CLAUDE.md §4 rule 5. [FieldDef] carries no separate description column
     * (`ui/generated/GeneratedFormScreen.kt:148` renders `fd.name` directly as the on-screen
     * label), so the field NAME itself is the one user-facing string this schema layer has, and it
     * says "estimated" rather than merely implying it - see the carve doc's field-mapping section
     * header note. */
    const val FIELD_ESTIMATED_CALORIES_KCAL = "estimatedCaloriesKcal"
    const val FIELD_ESTIMATED_PROTEIN_G = "estimatedProteinG"
    const val FIELD_ESTIMATED_CARBS_G = "estimatedCarbsG"
    const val FIELD_ESTIMATED_FAT_G = "estimatedFatG"

    /** One schema per record type - see [com.kevin.legion.engine.dates.DatesAspectSeeder.Schema]
     * for the identical shape/reasoning (field ids are `AUTOINCREMENT`, not known at compile time). */
    data class RecordSchema(val recordTypeId: Long, val fieldIds: Map<String, Long>)

    data class Schema(val aspectId: Long, val receipt: RecordSchema, val lineItem: RecordSchema)

    /** Idempotent at every granularity - see
     * [com.kevin.legion.engine.dates.DatesAspectSeeder.ensureSeeded]'s doc comment for the exact
     * mechanism (matched by name at each level, not a single top-level flag). */
    suspend fun ensureSeeded(context: Context): Schema {
        val db = CarDatabase.getDatabase(context)
        val now = System.currentTimeMillis()

        val aspectId = db.aspectDao().listActive().find { it.name == ASPECT_NAME }?.id
            ?: db.aspectDao().insert(
                Aspect(name = ASPECT_NAME, icon = "pantry", color = "", position = 2, createdAt = now, updatedAt = now),
            )

        val receiptTypeId = db.recordTypeDao().listByAspect(aspectId).find { it.name == RECEIPT_RECORD_TYPE_NAME }?.id
            ?: db.recordTypeDao().insert(
                RecordType(aspectId = aspectId, name = RECEIPT_RECORD_TYPE_NAME, createdAt = now, updatedAt = now),
            )
        val lineItemTypeId = db.recordTypeDao().listByAspect(aspectId).find { it.name == LINE_ITEM_RECORD_TYPE_NAME }?.id
            ?: db.recordTypeDao().insert(
                RecordType(aspectId = aspectId, name = LINE_ITEM_RECORD_TYPE_NAME, createdAt = now, updatedAt = now),
            )

        suspend fun ensureField(
            recordTypeId: Long,
            existing: Map<String, FieldDef>,
            name: String,
            type: FieldType,
            required: Boolean,
            position: Int,
            config: String? = null,
        ): Long {
            existing[name]?.let { return it.id }
            return db.fieldDefDao().insert(
                FieldDef(
                    recordTypeId = recordTypeId,
                    name = name,
                    type = type,
                    required = required,
                    position = position,
                    config = config,
                    ownerPluginId = OWNER_PLUGIN_ID,
                    locked = required,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }

        // ---- Receipt --------------------------------------------------------------------------
        val existingReceiptFields = db.fieldDefDao().forRecordType(receiptTypeId).associateBy { it.name }
        val receiptFieldIds = mutableMapOf<String, Long>()
        receiptFieldIds[FIELD_STORE] = ensureField(receiptTypeId, existingReceiptFields, FIELD_STORE, FieldType.TEXT, required = true, position = 0)
        receiptFieldIds[FIELD_PURCHASE_DATE] = ensureField(receiptTypeId, existingReceiptFields, FIELD_PURCHASE_DATE, FieldType.DATETIME, required = true, position = 1)
        receiptFieldIds[FIELD_CURRENCY] = ensureField(
            receiptTypeId, existingReceiptFields, FIELD_CURRENCY, FieldType.CHOICE, required = true, position = 2,
            config = FieldConfig.serializeChoice(CURRENCY_OPTIONS),
        )
        receiptFieldIds[FIELD_TOTAL] = ensureField(receiptTypeId, existingReceiptFields, FIELD_TOTAL, FieldType.MONEY_CENTS, required = true, position = 3)
        receiptFieldIds[FIELD_SOURCE_IMAGE_PATH] = ensureField(receiptTypeId, existingReceiptFields, FIELD_SOURCE_IMAGE_PATH, FieldType.TEXT, required = false, position = 4)
        // Cutover 2 anchors - see this file's own doc comment on the three constants above.
        receiptFieldIds[FIELD_SUBTOTAL] = ensureField(receiptTypeId, existingReceiptFields, FIELD_SUBTOTAL, FieldType.MONEY_CENTS, required = false, position = 5)
        receiptFieldIds[FIELD_TAX] = ensureField(receiptTypeId, existingReceiptFields, FIELD_TAX, FieldType.MONEY_CENTS, required = false, position = 6)
        receiptFieldIds[FIELD_OTHER_CHARGES] = ensureField(receiptTypeId, existingReceiptFields, FIELD_OTHER_CHARGES, FieldType.MONEY_CENTS, required = false, position = 7)

        // total is the receipt's own printed, gate-verified figure - promote it, same shape as
        // NotesAspectSeeder's primaryDueDateFieldId block. First seeder in the repo to set
        // primaryAmountFieldId (see the carve doc).
        val receiptType = db.recordTypeDao().getById(receiptTypeId)!!
        if (receiptType.primaryAmountFieldId != receiptFieldIds[FIELD_TOTAL]) {
            db.recordTypeDao().update(receiptType.copy(primaryAmountFieldId = receiptFieldIds[FIELD_TOTAL], updatedAt = now))
        }

        // ---- LineItem -------------------------------------------------------------------------
        val existingLineItemFields = db.fieldDefDao().forRecordType(lineItemTypeId).associateBy { it.name }
        val lineItemFieldIds = mutableMapOf<String, Long>()
        lineItemFieldIds[FIELD_RECEIPT] = ensureField(
            lineItemTypeId, existingLineItemFields, FIELD_RECEIPT, FieldType.REFERENCE, required = true, position = 0,
            config = FieldConfig.serializeReference(receiptTypeId, DeletePolicy.CASCADE),
        )
        lineItemFieldIds[FIELD_NAME] = ensureField(lineItemTypeId, existingLineItemFields, FIELD_NAME, FieldType.TEXT, required = true, position = 1)
        lineItemFieldIds[FIELD_QUANTITY] = ensureField(lineItemTypeId, existingLineItemFields, FIELD_QUANTITY, FieldType.NUMBER, required = true, position = 2)
        lineItemFieldIds[FIELD_UNIT_PRICE] = ensureField(lineItemTypeId, existingLineItemFields, FIELD_UNIT_PRICE, FieldType.MONEY_CENTS, required = false, position = 3)
        lineItemFieldIds[FIELD_TOTAL_PRICE] = ensureField(lineItemTypeId, existingLineItemFields, FIELD_TOTAL_PRICE, FieldType.MONEY_CENTS, required = true, position = 4)
        lineItemFieldIds[FIELD_ESTIMATED_CALORIES_KCAL] = ensureField(lineItemTypeId, existingLineItemFields, FIELD_ESTIMATED_CALORIES_KCAL, FieldType.NUMBER, required = false, position = 5)
        lineItemFieldIds[FIELD_ESTIMATED_PROTEIN_G] = ensureField(lineItemTypeId, existingLineItemFields, FIELD_ESTIMATED_PROTEIN_G, FieldType.NUMBER, required = false, position = 6)
        lineItemFieldIds[FIELD_ESTIMATED_CARBS_G] = ensureField(lineItemTypeId, existingLineItemFields, FIELD_ESTIMATED_CARBS_G, FieldType.NUMBER, required = false, position = 7)
        lineItemFieldIds[FIELD_ESTIMATED_FAT_G] = ensureField(lineItemTypeId, existingLineItemFields, FIELD_ESTIMATED_FAT_G, FieldType.NUMBER, required = false, position = 8)

        // totalPrice is the figure the gate itself verified (sum(items) == subtotal) - promote it.
        val lineItemType = db.recordTypeDao().getById(lineItemTypeId)!!
        if (lineItemType.primaryAmountFieldId != lineItemFieldIds[FIELD_TOTAL_PRICE]) {
            db.recordTypeDao().update(lineItemType.copy(primaryAmountFieldId = lineItemFieldIds[FIELD_TOTAL_PRICE], updatedAt = now))
        }

        return Schema(
            aspectId = aspectId,
            receipt = RecordSchema(receiptTypeId, receiptFieldIds),
            lineItem = RecordSchema(lineItemTypeId, lineItemFieldIds),
        )
    }
}
