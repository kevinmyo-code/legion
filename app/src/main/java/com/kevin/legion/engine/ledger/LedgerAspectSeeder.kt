package com.kevin.legion.engine.ledger

import android.content.Context
import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordType
import com.kevin.legion.engine.FieldConfig

/**
 * The built-in Ledger aspect - Wave 3 of `.scratch/aspect-engine/issues/21-migration-waves.md`
 * ("notes/lists/places, then pantry, then ledger, then fleet"). Follows
 * [com.kevin.legion.engine.pantry.PantryAspectSeeder]'s exact shape (idempotent at every
 * granularity, `ownerPluginId`/`locked` per ticket 11 answer point 1) - see that object's own doc
 * comment for the reasoning this one does not repeat.
 *
 * **The carve, in full, is `docs/architecture/wave3-carve-2026-08-23.md`.** Its headline finding,
 * unlike Wave 2's pantry (which has no quarantine/provisional state at all): a
 * [com.kevin.legion.data.local.LedgerTransaction] carries a REAL, three-valued provenance column
 * ([com.kevin.legion.data.local.IngestMethod]) that already matches
 * [com.kevin.legion.data.local.RecordProvenance]'s vocabulary one-for-one -
 * `DETERMINISTIC`/`LLM_RECONCILED`/`UNRECONCILED` - so the migration copier
 * ([com.kevin.legion.engine.migration.EngineDataMigrationWave3]) maps it DIRECTLY, never upgrading
 * or collapsing a value, and never defaulting an unrecognised one (a genuinely unexpected
 * `ingestMethod` value is a hard failure for that row, not a silent `USER`/`DETERMINISTIC` guess).
 *
 * Only one record type this wave: `Transaction`. There is no second, child record type the way
 * Wave 2 needed `LineItem` - a `LedgerTransaction` row is already the finest grain the legacy
 * schema has.
 */
object LedgerAspectSeeder {
    const val ASPECT_NAME = "Ledger"
    const val OWNER_PLUGIN_ID = "ledger"

    const val TRANSACTION_RECORD_TYPE_NAME = "Transaction"

    const val FIELD_SOURCE_FILE = "sourceFile"
    const val FIELD_ACCOUNT_ID = "accountId"
    const val FIELD_CURRENCY = "currency"
    const val FIELD_TXN_DATE = "txnDate"
    const val FIELD_DESCRIPTION = "description"
    const val FIELD_AMOUNT = "amount"
    const val FIELD_BALANCE = "balance"
    const val FIELD_LINE_REF = "lineRef"
    /**
     * Plain TEXT, not [FieldType.REFERENCE] - `ingested_files` (the per-file ingestion ledger) is
     * deliberately NOT a record type in this engine (see the carve doc's "stays plugin-internal"
     * table row), so there is nothing for a REFERENCE field to point at. This field carries the raw
     * `IngestedFile.driveFileId` string as-is, an audit breadcrumb only.
     */
    const val FIELD_SOURCE_FILE_ID = "sourceFileId"
    /**
     * Plain TEXT, not [FieldType.CHOICE] - unlike [com.kevin.legion.engine.pantry.PantryAspectSeeder.CURRENCY_OPTIONS]'s
     * fixed two-value currency set, [com.kevin.legion.data.local.Category] is Room-backed and
     * user-editable (its own doc comment: "so the set can be edited later without a schema
     * migration"), so a CHOICE field's fixed-at-seed-time `options` list would go stale the moment
     * a category was renamed or added. Carried as free text, matching the legacy column's own type.
     */
    const val FIELD_CATEGORY = "category"
    const val FIELD_CATEGORY_PENDING = "categoryPending"
    const val FIELD_PENDING_LOGGED_AT = "pendingLoggedAt"

    /** [com.kevin.legion.data.local.LedgerCurrency]'s names, duplicated here as plain strings
     * rather than a dependency on that enum - same "engine package never depends on a plugin
     * package" reasoning [com.kevin.legion.engine.pantry.PantryAspectSeeder.CURRENCY_OPTIONS]'s own
     * doc comment states. */
    val CURRENCY_OPTIONS = listOf("SGD", "USD")

    data class RecordSchema(val recordTypeId: Long, val fieldIds: Map<String, Long>)

    data class Schema(val aspectId: Long, val transaction: RecordSchema)

    /** Idempotent at every granularity - see
     * [com.kevin.legion.engine.dates.DatesAspectSeeder.ensureSeeded]'s doc comment for the exact
     * mechanism (matched by name at each level, not a single top-level flag). */
    suspend fun ensureSeeded(context: Context): Schema {
        val db = CarDatabase.getDatabase(context)
        val now = System.currentTimeMillis()

        val aspectId = db.aspectDao().listActive().find { it.name == ASPECT_NAME }?.id
            ?: db.aspectDao().insert(
                Aspect(name = ASPECT_NAME, icon = "ledger", color = "", position = 3, createdAt = now, updatedAt = now),
            )

        val txnTypeId = db.recordTypeDao().listByAspect(aspectId).find { it.name == TRANSACTION_RECORD_TYPE_NAME }?.id
            ?: db.recordTypeDao().insert(
                RecordType(aspectId = aspectId, name = TRANSACTION_RECORD_TYPE_NAME, createdAt = now, updatedAt = now),
            )

        suspend fun ensureField(
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
                    recordTypeId = txnTypeId,
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

        val existingFields = db.fieldDefDao().forRecordType(txnTypeId).associateBy { it.name }
        val fieldIds = mutableMapOf<String, Long>()
        fieldIds[FIELD_SOURCE_FILE] = ensureField(existingFields, FIELD_SOURCE_FILE, FieldType.TEXT, required = true, position = 0)
        fieldIds[FIELD_ACCOUNT_ID] = ensureField(existingFields, FIELD_ACCOUNT_ID, FieldType.TEXT, required = true, position = 1)
        fieldIds[FIELD_CURRENCY] = ensureField(
            existingFields, FIELD_CURRENCY, FieldType.CHOICE, required = true, position = 2,
            config = FieldConfig.serializeChoice(CURRENCY_OPTIONS),
        )
        // txnDate is deliberately NOT wired as primaryDueDateFieldId - a past, already-settled
        // transaction date is not an upcoming, actionable "due" the way a Notes reminder or a Dates
        // event is (same deliberate scope cut as PantryAspectSeeder's purchaseDate field, stated
        // there rather than defaulted into, and stated again here for the same reason).
        fieldIds[FIELD_TXN_DATE] = ensureField(existingFields, FIELD_TXN_DATE, FieldType.DATETIME, required = true, position = 3)
        fieldIds[FIELD_DESCRIPTION] = ensureField(existingFields, FIELD_DESCRIPTION, FieldType.TEXT, required = true, position = 4)
        fieldIds[FIELD_AMOUNT] = ensureField(existingFields, FIELD_AMOUNT, FieldType.MONEY_CENTS, required = true, position = 5)
        fieldIds[FIELD_BALANCE] = ensureField(existingFields, FIELD_BALANCE, FieldType.MONEY_CENTS, required = false, position = 6)
        fieldIds[FIELD_LINE_REF] = ensureField(existingFields, FIELD_LINE_REF, FieldType.TEXT, required = true, position = 7)
        fieldIds[FIELD_SOURCE_FILE_ID] = ensureField(existingFields, FIELD_SOURCE_FILE_ID, FieldType.TEXT, required = false, position = 8)
        fieldIds[FIELD_CATEGORY] = ensureField(existingFields, FIELD_CATEGORY, FieldType.TEXT, required = false, position = 9)
        fieldIds[FIELD_CATEGORY_PENDING] = ensureField(existingFields, FIELD_CATEGORY_PENDING, FieldType.BOOLEAN, required = true, position = 10)
        fieldIds[FIELD_PENDING_LOGGED_AT] = ensureField(existingFields, FIELD_PENDING_LOGGED_AT, FieldType.DATETIME, required = false, position = 11)

        // amount is the transaction's own signed, gate-relevant figure - promote it, same shape as
        // PantryAspectSeeder's `total`/`totalPrice` promotions.
        val txnType = db.recordTypeDao().getById(txnTypeId)!!
        if (txnType.primaryAmountFieldId != fieldIds[FIELD_AMOUNT]) {
            db.recordTypeDao().update(txnType.copy(primaryAmountFieldId = fieldIds[FIELD_AMOUNT], updatedAt = now))
        }

        return Schema(aspectId = aspectId, transaction = RecordSchema(txnTypeId, fieldIds))
    }
}
