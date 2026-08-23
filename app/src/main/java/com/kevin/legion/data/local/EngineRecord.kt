package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Provenance on every engine record (ticket 03 answer point 5, CLAUDE.md §4 rule 4) - a real
 * column, not payload, "the gate queries it". Deliberately a superset of [IngestMethod]:
 * [USER] is new here because the engine's records include plain hand-entered rows (a note, a
 * manually-added record) that [LedgerTransaction]/[PantryReceipt] never needed a tag for - those
 * two entities are always document-derived by construction, this one is not.
 */
enum class RecordProvenance { DETERMINISTIC, LLM_RECONCILED, UNRECONCILED, USER }

/**
 * One row of any record type in any aspect - the engine's single generic record table (charter
 * decision 3: "fixed tables + JSON payload + promoted hot columns, inside Room. No runtime DDL").
 * [com.kevin.legion.engine.RecordStore] is the ONLY thing that writes here (ticket 03 answer point
 * 3) - nothing else in the app, present or future, touches [EngineRecordDao]'s write methods
 * directly, matching [IngestedFile]'s own "never a driver-facing table" posture but for the
 * opposite reason: that table is infrastructure nobody edits, this one is the user's actual data
 * and the door exists to keep every write honest (reference existence, delete policy, computed
 * materialization, provenance) regardless of which caller - a meta-tool, a generated form, an
 * import gate, a capability plugin - is doing the writing.
 *
 * **Promoted columns** ([recordTypeId], [createdAt], [updatedAt], [dueAt], [amountCents],
 * [searchText], [provenance]) are ticket 03 answer point 1's "the standard set" - real columns so
 * cross-aspect queries (an agenda across every record type with a [dueAt], a spend total across
 * every record type with an [amountCents]) never have to parse [payload] JSON to run. Which
 * [FieldDef] mirrors into [dueAt]/[amountCents] for a given [recordTypeId] is declared on
 * [RecordType.primaryDueDateFieldId]/[RecordType.primaryAmountFieldId] - see that entity's doc
 * comment.
 *
 * **[payload]** carries everything else, keyed by [FieldDef.id] (as a JSON object string key) -
 * see [com.kevin.legion.engine.PayloadCodec] for the read/write contract. A brand-new aspect never
 * needs a schema change because its fields live here, not as columns.
 *
 * **[deletedAt] is the trash tombstone** (ticket 03 answer point 4: "record delete = trash, same
 * 30-day restore"), the record-level twin of [Aspect.archivedAt]. Null means live; non-null means
 * trashed at that instant and eligible for [EngineRecordDao.purgeDeletedBefore] once 30 days have
 * passed. A trashed record's row still exists in full - restoring is exactly clearing this column,
 * never a re-insert - so an aggregate a trashed record was feeding simply excludes it until
 * restored, without losing the row's own data in between.
 */
@Entity(
    tableName = "records",
    indices = [
        Index(value = ["recordTypeId"]),
        Index(value = ["dueAt"]),
        Index(value = ["deletedAt"]),
    ],
)
data class EngineRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordTypeId: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val dueAt: Long? = null,
    val amountCents: Long? = null,
    val searchText: String = "",
    val provenance: RecordProvenance,
    val payload: String = "{}",
    val deletedAt: Long? = null,
)
