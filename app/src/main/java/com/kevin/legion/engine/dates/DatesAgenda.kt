package com.kevin.legion.engine.dates

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordType
import com.kevin.legion.engine.PayloadCodec
import org.json.JSONObject

/**
 * **THE agenda source** (`.scratch/aspect-engine/issues/19-build-dates-aspect.md` point 3, locked
 * at `.scratch/aspect-engine/issues/05-central-date-database.md` answer point 4: "agenda is a
 * query, across the Dates aspect plus every record's dueAt column... one fact, one place"). Every
 * surface that shows "what's coming up" - `DatesAlarmScheduler`'s single armed alarm,
 * `OpenerCalendarBriefing`'s spoken greeting, and any future widget/voice surface - reads through
 * here rather than re-deriving its own notion of "due", the exact shape CLAUDE.md's "three bugs of
 * one shape" lesson names.
 *
 * Deliberately does NOT copy due dates into the Dates aspect, and does NOT special-case the Dates
 * record type in its own query - [EngineRecord.dueAt] is already promoted for every record type
 * that declares a `primaryDueDateFieldId` (ticket 03 answer point 1), so a plain
 * `dueAt IS NOT NULL` scan across `records` already covers a Dates event and a future fleet
 * service reminder identically. [AgendaItem.source] is the only field that only ever has a real
 * value for a Dates-aspect record; everything else in this file treats every record type the same.
 */
object DatesAgenda {

    /** One row of the merged agenda - a Dates event OR any other record type's own [EngineRecord.dueAt]. */
    data class AgendaItem(
        val recordId: Long,
        val recordTypeId: Long,
        val aspectId: Long,
        val title: String,
        val dueAt: Long,
        val endAt: Long?,
        val location: String?,
        /** "legion"/"google" for a Dates-aspect record; null for any other record type's dueAt -
         * only the Dates schema declares a field named [DatesAspectSeeder.FIELD_SOURCE] at all. */
        val source: String?,
        val muted: Boolean,
    )

    /** How many candidates [nextUnmuted] reads before giving up - a personal app's data volume,
     * not an enterprise queue; see [com.kevin.legion.engine.RecordStore]'s own class doc for the
     * same "simple and inspectable over clever" tradeoff at the same scale. */
    private const val NEXT_DUE_BATCH_SIZE = 25

    /** Every active record with a dueAt inside `[fromMs, toMs]`, across every aspect, ascending. */
    suspend fun windowed(context: Context, fromMs: Long, toMs: Long): List<AgendaItem> {
        val db = CarDatabase.getDatabase(context)
        return toAgendaItems(db, db.engineRecordDao().activeWithDueAtInWindow(fromMs, toMs))
    }

    /** One specific record, resolved into an [AgendaItem] if it is live and has a dueAt at all -
     * what [com.kevin.legion.service.DatesReminderAlarmReceiver] reads the instant its one armed
     * alarm fires, so it never has to re-derive a window around a single known id. */
    suspend fun byId(context: Context, recordId: Long): AgendaItem? {
        val db = CarDatabase.getDatabase(context)
        val record = db.engineRecordDao().getById(recordId) ?: return null
        if (record.deletedAt != null || record.dueAt == null) return null
        return toAgendaItems(db, listOf(record)).firstOrNull()
    }

    /** The single soonest active, UNMUTED record due at or after [afterMs] - what
     * `service/DatesAlarmScheduler.kt`'s one alarm is always armed against. Reads a bounded batch
     * ([NEXT_DUE_BATCH_SIZE]) rather than the whole table so a long run of muted reminders cannot
     * make this scan unbounded. */
    suspend fun nextUnmuted(context: Context, afterMs: Long = System.currentTimeMillis()): AgendaItem? {
        val db = CarDatabase.getDatabase(context)
        val candidates = db.engineRecordDao().activeWithDueAtFrom(afterMs, NEXT_DUE_BATCH_SIZE)
        return toAgendaItems(db, candidates).firstOrNull { !it.muted }
    }

    private suspend fun toAgendaItems(db: CarDatabase, records: List<EngineRecord>): List<AgendaItem> {
        if (records.isEmpty()) return emptyList()
        val recordTypeCache = mutableMapOf<Long, RecordType?>()
        val fieldDefCache = mutableMapOf<Long, List<FieldDef>>()
        val mutedIds = db.mutedReminderDao().mutedRecordIds(records.map { it.id }).toSet()

        return records.mapNotNull { record ->
            val dueAt = record.dueAt ?: return@mapNotNull null // callers only ever pass dueAt-bearing rows; defensive, never trusted blindly
            val recordType = recordTypeCache.getOrPut(record.recordTypeId) { db.recordTypeDao().getById(record.recordTypeId) }
                ?: return@mapNotNull null // the record type was deleted out from under this record - skip it, never crash the whole agenda over one orphan
            val fieldDefs = fieldDefCache.getOrPut(record.recordTypeId) { db.fieldDefDao().forRecordType(record.recordTypeId) }
            val payload = JSONObject(record.payload)

            // Title resolution heuristic, stated so it is not mistaken for a locked contract: the
            // first TEXT-type field by form position, falling back to the promoted searchText, then
            // to a literal placeholder. This works for the Dates schema (title is TEXT at
            // position 0) and for any future record type whose first text field is its natural
            // label; it is a reasonable v1 default, not a designed "title field" concept the engine
            // schema itself declares (ticket 03 has no primaryTitleFieldId, unlike
            // primaryAmountFieldId/primaryDueDateFieldId).
            val titleField = fieldDefs.filter { it.type == FieldType.TEXT }.minByOrNull { it.position }
            val title = titleField?.let { PayloadCodec.readString(payload, it.id) }?.takeIf { it.isNotBlank() }
                ?: record.searchText.ifBlank { "(untitled)" }

            val endField = fieldDefs.find { it.name == DatesAspectSeeder.FIELD_END }
            val locationField = fieldDefs.find { it.name == DatesAspectSeeder.FIELD_LOCATION }
            val sourceField = fieldDefs.find { it.name == DatesAspectSeeder.FIELD_SOURCE }

            AgendaItem(
                recordId = record.id,
                recordTypeId = record.recordTypeId,
                aspectId = recordType.aspectId,
                title = title,
                dueAt = dueAt,
                endAt = endField?.let { PayloadCodec.readLong(payload, it.id) },
                location = locationField?.let { PayloadCodec.readString(payload, it.id) }?.takeIf { it.isNotBlank() },
                source = sourceField?.let { PayloadCodec.readString(payload, it.id) },
                muted = record.id in mutedIds,
            )
        }.sortedBy { it.dueAt }
    }
}
