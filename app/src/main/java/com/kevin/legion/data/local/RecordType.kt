package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One record shape inside an [Aspect] - "Vehicle", "Service", "Transaction", "Receipt", each a
 * row here rather than a Room `@Entity`. Its [FieldDef] children are the actual column list.
 *
 * [primaryAmountFieldId]/[primaryDueDateFieldId] are how the engine knows WHICH [FieldDef] mirrors
 * into [EngineRecord]'s promoted `amountCents`/`dueAt` columns (ticket 03 answer point 1: "the
 * standard set" is promoted for every record, but a record type can define at most one field of
 * each kind that actually deserves the promotion - a `record_types` row is the one place that
 * mapping is declared, so [com.kevin.legion.engine.RecordStore] never has to guess which of
 * several MONEY_CENTS fields on a type is "the" total). Both nullable: a record type with no
 * money-shaped or date-shaped field at all (a plain note list) simply never populates the
 * promoted column, and a query that sorts/filters by it sees nothing from that type - never a
 * fabricated zero or an arbitrary field guessed at.
 *
 * No `@ForeignKey` to [FieldDef], matching this schema's established convention
 * ([LedgerTransaction.sourceFileId]'s doc comment) of never letting a delete cascade into data it
 * did not obviously own - deleting a field definition is a schema edit
 * ([com.kevin.legion.engine.RecordStore] and the schema-generator subagent's job, ticket 03 answer
 * point 5), never an implicit side effect of this table.
 */
@Entity(tableName = "record_types", indices = [Index(value = ["aspectId"])])
data class RecordType(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val aspectId: Long,
    val name: String,
    val primaryAmountFieldId: Long? = null,
    val primaryDueDateFieldId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
