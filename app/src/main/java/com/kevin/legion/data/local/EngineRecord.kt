package com.kevin.legion.data.local

import androidx.room.ColumnInfo
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
 *
 * **[guid] is the record's globally-stable cross-device identity** (v37, senior review of ticket
 * 20's mirror/sync: MUST-FIX 1). [id] is a per-database `AUTOINCREMENT` primary key - it is NOT
 * comparable across two phones, since each device mints its own sequence independently. A record
 * created on phone A as local id 5 and a completely unrelated record created on phone B, also as
 * local id 5, are two different rows that happen to share a number; the mirror's original design
 * exported and matched rows on [id] directly, which meant a record created on one device either
 * quarantined forever on import to the other (worded, wrongly, as "no longer exists locally" - it
 * never existed there under that number to begin with) or, worse, could silently overwrite an
 * unrelated local record that happened to share the same id. [guid] fixes this by giving every
 * record an identity that means the same thing on every device: [com.kevin.legion.engine.mirror.MirrorCodec]
 * exports [guid] (never [id]) as the mirror's identity column, and
 * [com.kevin.legion.engine.mirror.MirrorSync] matches an imported row against a local record by
 * [guid] - [id] never leaves the device it was assigned on. Same `@ColumnInfo(defaultValue = "''")`
 * plus Kotlin-level `UUID.randomUUID()` construction-time default as [AdvisorAdvice.syncId], the
 * one existing precedent for this exact shape in this codebase. [MIGRATION_36_37] backfills every
 * pre-existing row with a real, distinct UUID at migration time (never leaving a row `''`), and
 * [EngineRecordDao.getByGuid] is the mirror's only lookup path.
 *
 * **[widget_instances]/[muted_reminders] keep referencing a record by its LOCAL [id], and that is
 * correct, not an oversight**: neither table is exported by the mirror or synced across devices
 * (widget layouts are deliberately per-device, ticket 08 answer; a reminder mute is a per-device
 * dismissal, [MutedReminder]'s own doc comment) - both stay device-local infrastructure, so the
 * cross-device identity problem [guid] solves simply does not apply to them.
 */
@Entity(
    tableName = "records",
    indices = [
        Index(value = ["recordTypeId"]),
        Index(value = ["dueAt"]),
        Index(value = ["deletedAt"]),
        Index(value = ["guid"], unique = true),
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
    @ColumnInfo(defaultValue = "''") val guid: String = java.util.UUID.randomUUID().toString(),
)
