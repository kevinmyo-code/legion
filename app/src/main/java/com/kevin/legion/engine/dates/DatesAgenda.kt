package com.kevin.legion.engine.dates

import android.content.Context
import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordType
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.migration.EngineNotesRetirementCopy
import com.kevin.legion.engine.notes.NotesAspectSeeder
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
 * **Repointed off the engine's Dates record type (backend-erp ticket 17, "RULED 2026-08-28": Dates
 * repoints onto the SAME `events` table Notes already uses).** This file now merges TWO sources
 * rather than reading the engine alone:
 *
 * 1. **The local `events` table, `kind = `[EventKind.EVENT]** -
 *    [com.kevin.legion.calendar.CalendarImportController] writes here directly now, no engine
 *    involved, so this is the live, current Dates data.
 * 2. **The engine's `dueAt` scan, EXCLUDING the Dates and Notes aspects** - the cross-aspect merge
 *    this class has always promised ("every record's dueAt column", not just Dates) is preserved
 *    for any OTHER aspect that declares [RecordType.primaryDueDateFieldId] (none does today,
 *    confirmed by grep before this change, but [DatesAgendaTest]'s own synthetic "Tasks" record
 *    type exists specifically to prove this merge is real, not vestigial). Dates and Notes are
 *    excluded here because both retired their own engine writers onto `events`
 *    (backend-erp ticket 15 step 4 for Notes, this repoint for Dates) - their OLD engine rows are a
 *    frozen historical snapshot (nothing deletes them, CLAUDE.md's "nothing deleted from the
 *    engine" posture) that would otherwise double-count against the live `events`-table read above.
 *
 * **A residual, narrow risk this repoint introduces, stated rather than hidden.** [AgendaItem.recordId]
 * now comes from TWO independent autoincrement id spaces - [Event.id] and [EngineRecord.id] - which
 * could in principle collide (an `events` row and a `records` row coincidentally sharing the same
 * numeric id). [byId] tries the `events` table first specifically because Dates is by far the
 * common case today; a true collision would only matter the day a second aspect actually starts
 * using [RecordType.primaryDueDateFieldId], which none does yet. Flagged as a reasoned, accepted
 * gap rather than solved with a namespacing scheme this ticket did not ask for.
 */
object DatesAgenda {

    /** One row of the merged agenda - a Dates event (from `events`) OR any other record type's own
     * [EngineRecord.dueAt] (from the engine, Dates/Notes excluded - see this object's own class
     * doc). */
    data class AgendaItem(
        val recordId: Long,
        val title: String,
        val dueAt: Long,
        val endAt: Long?,
        val location: String?,
        /** "legion"/"google" for a Dates-sourced item; null for any other record type's dueAt -
         * only the Dates schema/table has a source concept at all. */
        val source: String?,
        val muted: Boolean,
        /** True when [dueAt] is NOT a date the user stated - ticket 01 ruling 2
         * (`.scratch/backend-erp/issues/01-what-the-backend-owns.md:42-44`): an undated todo
         * (a record whose type declares a due-date field the user left blank) still shows up here
         * with `dueAt` = "tomorrow, computed from now", so the agenda is never 95% empty just
         * because most of what got typed in has no date attached. Storage is untouched - the
         * underlying row's own due-date column stays NULL forever (CLAUDE.md sec 4 rule 5: an
         * inferred fact is never written as if it were stated) - this flag exists so every caller
         * can tell the two apart without re-deriving the distinction itself. **A caller MUST render
         * this in words** ("showing tomorrow, no date set" or equivalent) rather than as a bare
         * date, and MUST exclude it from anything that can nag or go overdue - see [nextUnmuted]'s
         * own doc comment for where that second half is actually enforced. */
        val dueIsInferred: Boolean,
    )

    /** How many candidates [nextUnmuted] reads (PER SOURCE - engine and `events` each get their own
     * bounded batch before the merge) - a personal app's data volume, not an enterprise queue; see
     * [com.kevin.legion.engine.RecordStore]'s own class doc for the same "simple and inspectable
     * over clever" tradeoff at the same scale. */
    private const val NEXT_DUE_BATCH_SIZE = 25

    /** How far ahead of "now" an inferred (undated) row shows as due - ticket 01 ruling 2's literal
     * "due=tomorrow". A fixed 24-hour offset from the read-time "now" rather than a calendar-day
     * boundary: it needs no timezone to compute, it is trivially "always ahead of now, never in
     * the past" (ruling clause 4, "rolls forward silently"), and nothing in the ruling asks for
     * midnight-alignment precision - it only asks that it read as "tomorrow" and never go stale. */
    private const val INFERRED_DUE_OFFSET_MS = 24L * 60 * 60 * 1000

    /** Aspect names excluded from the generic engine dueAt scan - see this object's own class doc,
     * point 2, for why. */
    private val EXCLUDED_ENGINE_ASPECTS = listOf(DatesAspectSeeder.ASPECT_NAME, NotesAspectSeeder.ASPECT_NAME)

    /** Gate every read below on the one-time engine-to-`events` copy having run - mirrors
     * [com.kevin.legion.notes.NotesController.ensureLegacyReconciled]'s identical posture for the
     * identical reason: a pre-repoint Dates `Event` still sitting only in the engine (nothing
     * deletes it - CLAUDE.md's "nothing deleted from the engine" posture) must not silently vanish
     * from the agenda the moment this file stops reading the engine directly. Cheap after the first
     * call - [EngineNotesRetirementCopy.copyIfNeeded] itself short-circuits on its own completion
     * flag. **Deliberately NOT gated on Notes ever having been opened first** - before this call
     * existed, the copy only ever ran as a side effect of a Notes read/write, which means a device
     * that used Dates (via [com.kevin.legion.service.DatesAlarmScheduler]'s app-start `armNext`)
     * before ever touching Notes would see nothing from before the repoint until Notes happened to
     * run it. Every function below calls this first, matching [com.kevin.legion.notes.NotesController]'s
     * own "every unconfigured function calls this before touching `events`" discipline. */
    private suspend fun ensureLegacyReconciled(context: Context) {
        EngineNotesRetirementCopy.copyIfNeeded(context)
    }

    /** Every active [Event] with `kind = `[EventKind.EVENT] - the one place both [windowed]
     * and [nextUnmuted] read the local table from, so a future third caller never has to re-derive
     * the kind filter itself. */
    private suspend fun activeAppointments(db: CarDatabase): List<Event> =
        db.eventDao().getActiveByKind(EventKind.EVENT)

    /** Every active record with a promoted [EngineRecord.dueAt] inside `[fromMs, toMs]`, PLUS
     * every undated todo (ticket 01 ruling 2), whose inferred "tomorrow" is computed fresh
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
        ensureLegacyReconciled(context)

        val appointments = activeAppointments(db)
        val mutedIds = db.mutedReminderDao().mutedRecordIds(appointments.map { it.id }).toSet()
        val eventItems = appointments.mapNotNull { toAgendaItemFromEvent(it, nowMs, mutedIds) }

        val dated = db.engineRecordDao().activeWithDueAtInWindow(fromMs, toMs, EXCLUDED_ENGINE_ASPECTS)
        val undated = db.engineRecordDao().activeUndatedWithDueConcept(EXCLUDED_ENGINE_ASPECTS)
        val engineItems = toAgendaItemsFromEngine(db, dated, nowMs) + toAgendaItemsFromEngine(db, undated, nowMs)

        // The undated fetches above are NOT themselves windowed by SQL (there is nothing stored to
        // filter on) - filter here, after the inferred dueAt has been computed, so an inferred date
        // only shows up in a window that actually contains it, the same as a real one would.
        return (eventItems + engineItems).filter { it.dueAt in fromMs..toMs }.sortedBy { it.dueAt }
    }

    /** One specific record, resolved into an [AgendaItem] if it is live and has a real (non-
     * inferred) due date - what [com.kevin.legion.service.DatesReminderAlarmReceiver] reads the
     * instant its one armed alarm fires, so it never has to re-derive a window around a single
     * known id. Tries the `events` table first (the common case - see this object's own class doc
     * for the residual id-space risk that ordering exists to minimize), then falls back to the
     * engine, excluding Dates/Notes there for the same reason [windowed] does. Guards on a real due
     * date before returning in either branch, so an inferred date can never reach here even in
     * principle - matching ticket 01 ruling 2's "never overdue, never nags". */
    suspend fun byId(context: Context, recordId: Long): AgendaItem? {
        val db = CarDatabase.getDatabase(context)
        ensureLegacyReconciled(context)

        val event = db.eventDao().getById(recordId)
        if (event != null) {
            if (event.deleted || event.kind != EventKind.EVENT || event.startsAt == null) return null
            val mutedIds = db.mutedReminderDao().mutedRecordIds(listOf(event.id)).toSet()
            return toAgendaItemFromEvent(event, System.currentTimeMillis(), mutedIds)
        }

        val record = db.engineRecordDao().getById(recordId) ?: return null
        if (record.deletedAt != null || record.dueAt == null) return null
        val recordType = db.recordTypeDao().getById(record.recordTypeId) ?: return null
        val aspect = db.aspectDao().listActive().find { it.id == recordType.aspectId } ?: return null
        if (aspect.name in EXCLUDED_ENGINE_ASPECTS) return null
        return toAgendaItemsFromEngine(db, listOf(record), System.currentTimeMillis()).firstOrNull()
    }

    /** The single soonest active, UNMUTED item due at or after [afterMs], merged across both
     * sources - what `service/DatesAlarmScheduler.kt`'s one alarm is always armed against. Reads a
     * bounded batch from each source ([NEXT_DUE_BATCH_SIZE]) rather than the whole table so a long
     * run of muted reminders cannot make this scan unbounded.
     *
     * **Deliberately never merges in an undated todo.** Neither [com.kevin.legion.data.local.EventDao.activeByKindFrom]
     * nor [com.kevin.legion.data.local.EngineRecordDao.activeWithDueAtFrom] (this method's two
     * sources) ever returns a null-due row - both hard-filter it out in SQL - so an inferred-date
     * row is excluded structurally, not by a flag check someone could get wrong later - this is
     * where ticket 01 ruling 2's "never overdue, never nags" is actually enforced. An inferred date
     * arming a real `AlarmManager` alarm would be the app asserting a deadline the user never set
     * (CLAUDE.md sec 7's compulsion test, clause (a): the anchor must be a fact the user could
     * verify himself, and a date nobody typed in fails that outright). */
    suspend fun nextUnmuted(context: Context, afterMs: Long = System.currentTimeMillis()): AgendaItem? {
        val db = CarDatabase.getDatabase(context)
        ensureLegacyReconciled(context)

        val eventCandidates = db.eventDao().activeByKindFrom(EventKind.EVENT, afterMs, NEXT_DUE_BATCH_SIZE)
        val eventMutedIds = db.mutedReminderDao().mutedRecordIds(eventCandidates.map { it.id }).toSet()
        val eventItems = eventCandidates.mapNotNull { toAgendaItemFromEvent(it, afterMs, eventMutedIds) }

        val engineCandidates = db.engineRecordDao().activeWithDueAtFrom(afterMs, NEXT_DUE_BATCH_SIZE, EXCLUDED_ENGINE_ASPECTS)
        val engineItems = toAgendaItemsFromEngine(db, engineCandidates, afterMs)

        return (eventItems + engineItems).sortedBy { it.dueAt }.firstOrNull { !it.muted }
    }

    /** Flat mapping, no [FieldDef]/[PayloadCodec] involved - an [Event] row already carries every
     * column [AgendaItem] needs directly. Returns null only when [Event.startsAt] is null AND this
     * particular caller did not want an inferred one ([byId] never calls this at all in that case;
     * every OTHER caller wants the inferred row, so this always synthesizes one instead of ever
     * returning null for a null [Event.startsAt] here). */
    private fun toAgendaItemFromEvent(event: Event, nowMs: Long, mutedIds: Set<Long>): AgendaItem {
        val dueIsInferred = event.startsAt == null
        val dueAt = event.startsAt ?: (nowMs + INFERRED_DUE_OFFSET_MS)
        return AgendaItem(
            recordId = event.id,
            title = event.title,
            dueAt = dueAt,
            endAt = event.endsAt,
            location = event.location,
            source = event.source,
            muted = event.id in mutedIds,
            dueIsInferred = dueIsInferred,
        )
    }

    /** Converts raw [EngineRecord]s into [AgendaItem]s, synthesizing an inferred "tomorrow" dueAt
     * (from [nowMs]) for any record whose own [EngineRecord.dueAt] is null. Safe to do
     * unconditionally here rather than gating per-caller: [byId] and [nextUnmuted] never hand this
     * function a null-dueAt record in the first place (see their own doc comments for where each
     * one guarantees that), so the inferred branch below is live only for [windowed]'s undated
     * fetch. Unchanged from before this repoint except for the aspect exclusion already applied by
     * the caller's own query. */
    private suspend fun toAgendaItemsFromEngine(db: CarDatabase, records: List<EngineRecord>, nowMs: Long): List<AgendaItem> {
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
            // to a literal placeholder. This works for any record type whose first text field is
            // its natural label; it is a reasonable v1 default, not a designed "title field"
            // concept the engine schema itself declares (ticket 03 has no primaryTitleFieldId,
            // unlike primaryAmountFieldId/primaryDueDateFieldId).
            val titleField = fieldDefs.filter { it.type == FieldType.TEXT }.minByOrNull { it.position }
            val title = titleField?.let { PayloadCodec.readString(payload, it.id) }?.takeIf { it.isNotBlank() }
                ?: record.searchText.ifBlank { "(untitled)" }

            AgendaItem(
                recordId = record.id,
                title = title,
                dueAt = dueAt,
                endAt = null,
                location = null,
                source = null,
                muted = record.id in mutedIds,
                dueIsInferred = dueIsInferred,
            )
        }.sortedBy { it.dueAt }
    }
}
