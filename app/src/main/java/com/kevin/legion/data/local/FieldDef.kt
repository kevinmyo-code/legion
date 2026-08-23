package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The v1 field-type vocabulary, locked at
 * `.scratch/aspect-engine/issues/03-engine-schema.md` answer point 2. Duration is deliberately
 * absent (deferred to v2, same ticket). Stored as plain `TEXT` on [FieldDef.type] with no `CHECK`
 * constraint - widening this enum later needs no migration, the same "widening an enum stored as
 * TEXT is not a migration" convention [LedgerTransaction.ingestMethod] already established
 * (CLAUDE.md §5).
 */
enum class FieldType {
    TEXT,
    NUMBER,
    /** Long cents, never Double - CLAUDE.md §4 rule 3, same discipline as [LedgerTransaction.amountCents]. */
    MONEY_CENTS,
    DATE,
    DATETIME,
    BOOLEAN,
    CHOICE,
    MULTI_SELECT_CHOICE,
    /** Stores a target [EngineRecord.id]; existence-on-write and delete-policy enforcement both
     * live in [com.kevin.legion.engine.RecordStore], never in SQL (ticket 03 answer point 3). */
    REFERENCE,
    PHOTO,
    LOCATION,
    RATING,
    /** Materialized on write by [com.kevin.legion.engine.RecordStore] - never evaluated on read.
     * See [com.kevin.legion.engine.ComputedExpression] for the vocabulary (aggregation over
     * referencing children, or same-record arithmetic) locked at ticket 04. */
    COMPUTED,
}

/**
 * Per-field delete policy for a [FieldType.REFERENCE] field (ticket 03 answer point 3, ticket 11
 * answer point 1). Read out of [FieldDef.config] by
 * [com.kevin.legion.engine.FieldConfig.referenceConfig] - never a SQL constraint, since SQLite
 * foreign keys cannot express "quarantine at the import gate" or "detach a plugin" the way this
 * engine's own write door needs to.
 *
 * [NULLIFY] is spelled out rather than `NULL` (the charter's own word) because `null` is a
 * reserved identifier in Kotlin and every other language this enum's name might get echoed into
 * (SQL itself included, where `NULL` is also a keyword) - the meaning is unchanged.
 */
enum class DeletePolicy { BLOCK, CASCADE, NULLIFY }

/**
 * One column of a [RecordType] - the schema layer's actual "add a field" unit. A brand-new aspect
 * authored entirely at runtime (charter decision 1) is nothing more than rows in [Aspect],
 * [RecordType], and this table; there is no runtime DDL (charter decision 3) because there never
 * needs to be one.
 *
 * [config] is a JSON blob whose shape depends on [type] - choice options for
 * [FieldType.CHOICE]/[FieldType.MULTI_SELECT_CHOICE], a reference target and [DeletePolicy] for
 * [FieldType.REFERENCE], a [com.kevin.legion.engine.ComputedExpression] for [FieldType.COMPUTED].
 * Parsed exclusively through [com.kevin.legion.engine.FieldConfig] so every reader agrees on the
 * shape; this entity itself stays opaque to it, matching [CompanionProfileEntity.persona]'s own
 * "the entity does not interpret its own payload" convention.
 *
 * [ownerPluginId]/[locked] are ticket 11 answer point 1's "partially editable" contract: a
 * capability plugin (fleet/ledger/pantry, once migrated) declares the fields it needs, those come
 * back [locked] `= true` and carry the plugin's id so the UI can badge them, and everything else
 * on the same record type stays fully user-ownable (add, reorder, relabel, delete). A plugin is
 * never wired to actually SET these two columns by this ticket - that is migration-wave work
 * (CLAUDE.md §2's "carry-over inventory" shape) - but the schema must not preclude the answer
 * (ticket 03 answer point 4's instruction, applied to plugin binding instead of aspect delete), so
 * the columns exist now rather than becoming a later migration.
 */
@Entity(tableName = "field_defs", indices = [Index(value = ["recordTypeId"])])
data class FieldDef(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordTypeId: Long,
    val name: String,
    val type: FieldType,
    val required: Boolean = false,
    /** Form/list ordering within the record type. */
    val position: Int = 0,
    val config: String? = null,
    /** Non-null only for a field a capability plugin declared required - see this entity's doc. */
    val ownerPluginId: String? = null,
    /** True only alongside a non-null [ownerPluginId] - a user-authored field is never locked. */
    val locked: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)
