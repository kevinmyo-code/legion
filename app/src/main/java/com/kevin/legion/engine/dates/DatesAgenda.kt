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
        /** True when [dueAt] is NOT a date the user stated - ticket 01 ruling 2
         * (`.scratch/backend-erp/issues/01-what-the-backend-owns.md:42-44`): an undated todo
         * (a record whose type declares a due-date field the user left blank) still shows up here
         * with `dueAt` = "tomorrow, computed from now", so the agenda is never 95% empty just
         * because most of what got typed in has no date attached. Storage is untouched - the
         * `records.dueAt` column stays NULL forever (CLAUDE.md sec 4 rule 5: an inferred fact is
         * never written as if it were stated) - this flag exists so every caller can tell the two
         * apart without re-deriving the distinction itself. **A caller MUST render this in words**
         * ("showing tomorrow, no date set" or equivalent) rather than as a bare date, and MUST
         * exclude it from anything that can nag or go overdue - see [nextUnmuted]'s own doc
         * comment for where that second half is actually enforced. */
        val dueIsInferred: Boolean,
    )

    /** How many candidates [nextUnmuted] reads before giving up - a personal app's data volume,
     * not an enterprise queue; see [com.kevin.legion.engine.RecordStore]'s own class doc for the
     * same "simple and inspectable over clever" tradeoff at the same scale. */
    private const val NEXT_DUE_BATCH_SIZE = 25

    /** How far ahead of "now" an inferred (undated) row shows as due - ticket 01 ruling 2's literal
     * "due=tomorrow". A fixed 24-hour offset from the read-time "now" rather than a calendar-day
     * boundary: it needs no timezone to compute, it is trivially "always ahead of now, never in
     * the past" (ruling clause 4, "rolls forward silently"), and nothing in the ruling asks for
     * midnight-alignment precision - it only asks that it read as "tomorrow" and never go stale. */
    private const val INFERRED_DUE_OFFSET_MS = 24L * 60 * 60 * 1000

    /** Every active record with a dueAt inside `[fromMs, toMs]`, across every aspect, ascending -
     * PLUS every undated todo (ticket 01 ruling 2), whose inferred "tomorrow" is computed fresh
     * from [nowMs] on every call and then windowed exactly like a real dueAt would be. [nowMs] is
     * a parameter rather than an internal `System.currentTimeMillis()` call so a test can pin it
     * and so the "rolls forward, never cached" behaviour is visible at the call site. */
    suspend fun windowed(
        context: Context,
        fromMs: Long,
        toMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): List<AgendaItem> {
        val db = CarDatabase.getDatabase(context)
        val dated = db.engineRecordDao().activeWithDueAtInWindow(fromMs, toMs)
        val undated = db.engineRecordDao().activeUndatedWithDueConcept()
        val items = toAgendaItems(db, dated, nowMs) + toAgendaItems(db, undated, nowMs)
        // The undated fetch above is NOT itself windowed by SQL (there is nothing stored to filter
        // on) - filter here, after the inferred dueAt has been computed, so an inferred date only
        // shows up in a window that actually contains it, the same as a real one would.
        return items.filter { it.dueAt in fromMs..toMs }.sortedBy { it.dueAt }
    }

    /** One specific record, resolved into an [AgendaItem] if it is live and has a dueAt at all -
     * what [com.kevin.legion.service.DatesReminderAlarmReceiver] reads the instant its one armed
     * alarm fires, so it never has to re-derive a window around a single known id. Guards on a real
     * [EngineRecord.dueAt] before calling [toAgendaItems] at all, so an inferred date can never
     * reach here even in principle - matching ticket 01 ruling 2's "never overdue, never nags",
     * since this is the exact read the alarm fire path (`DatesReminderAlarmReceiver.fire`) depends
     * on to build the spoken/notified reminder. */
    suspend fun byId(context: Context, recordId: Long): AgendaItem? {
        val db = CarDatabase.getDatabase(context)
        val record = db.engineRecordDao().getById(recordId) ?: return null
        if (record.deletedAt != null || record.dueAt == null) return null
        return toAgendaItems(db, listOf(record), System.currentTimeMillis()).firstOrNull()
    }

    /** The single soonest active, UNMUTED record due at or after [afterMs] - what
     * `service/DatesAlarmScheduler.kt`'s one alarm is always armed against. Reads a bounded batch
     * ([NEXT_DUE_BATCH_SIZE]) rather than the whole table so a long run of muted reminders cannot
     * make this scan unbounded.
     *
     * **Deliberately never merges in an undated todo.** [EngineRecordDao.activeWithDueAtFrom]
     * (this method's only source) hard-filters `dueAt IS NOT NULL` in SQL, so an inferred-date row
     * is excluded structurally, not by a flag check someone could get wrong later - this is where
     * ticket 01 ruling 2's "never overdue, never nags" is actually enforced. An inferred date
     * arming a real `AlarmManager` alarm would be the app asserting a deadline the user never set
     * (CLAUDE.md sec 7's compulsion test, clause (a): the anchor must be a fact the user could
     * verify himself, and a date nobody typed in fails that outright). */
    suspend fun nextUnmuted(context: Context, afterMs: Long = System.currentTimeMillis()): AgendaItem? {
        val db = CarDatabase.getDatabase(context)
        val candidates = db.engineRecordDao().activeWithDueAtFrom(afterMs, NEXT_DUE_BATCH_SIZE)
        return toAgendaItems(db, candidates, afterMs).firstOrNull { !it.muted }
    }

    /** Converts raw [EngineRecord]s into [AgendaItem]s, synthesizing an inferred "tomorrow" dueAt
     * (from [nowMs]) for any record whose own [EngineRecord.dueAt] is null. Safe to do
     * unconditionally here rather than gating per-caller: [byId] and [nextUnmuted] never hand this
     * function a null-dueAt record in the first place (see their own doc comments for where each
     * one guarantees that), so the inferred branch below is live only for [windowed]'s undated
     * fetch. */
    private suspend fun toAgendaItems(db: CarDatabase, records: List<EngineRecord>, nowMs: Long): List<AgendaItem> {
        if (records.isEmpty()) return emptyList()
        val recordTypeCache = mutableMapOf<Long, RecordType?>()
        val fieldDefCache = mutableMapOf<Long, List<FieldDef>>()
        val mutedIds = db.mutedReminderDao().mutedRecordIds(records.map { it.id }).toSet()

        return records.mapNotNull { record ->
            val dueIsInferred = record.dueAt == null
            val dueAt = record.dueAt ?: (nowMs + INFERRED_DUE_OFFSET_MS)
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
                dueIsInferred = dueIsInferred,
            )
        }.sortedBy { it.dueAt }
    }
}
