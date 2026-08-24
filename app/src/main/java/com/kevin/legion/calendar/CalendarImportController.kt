package com.kevin.legion.calendar

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.dates.DatesAspectSeeder
import org.json.JSONObject

/**
 * Google Calendar import as a one-way feed into the Dates aspect
 * (`.scratch/aspect-engine/issues/19-build-dates-aspect.md` point 2, locked at
 * `.scratch/aspect-engine/issues/05-central-date-database.md` answer point 2/3: "store everything
 * imported, invites included... tagged source=google plus event id; re-import updates in place;
 * Google-side deletions mirror; one-way in v1"). Reuses [CalendarProvider.eventsInWindow] verbatim
 * - the same query `ui/TodayScreen.kt`/`ui/NotesScreen.kt`/the sitrep already read, never a second
 * one - and writes through [RecordStore], the engine's single door, tagged
 * [RecordProvenance.DETERMINISTIC]: this is a straight field-by-field mechanical copy, not an LLM
 * extraction, so CLAUDE.md sec 4's reconciliation gate does not apply here at all -
 * [com.kevin.legion.engine.ReconciliationGate]'s own doc comment is explicit that ticket 16 rehomed
 * the GATE's CONTRACT, not a requirement that every engine write pass through it; a gate exists to
 * verify extracted rows against a document's own stated total, and a calendar has no such anchor to
 * check against in the first place.
 *
 * **The composite key.** [CalendarProvider.GoogleCalendarEvent.eventId] is the PARENT event's id -
 * every occurrence of a recurring series shares the same one (`CalendarProvider`'s own class doc:
 * "a repeating Google event contributes one row per occurrence... never a raw RRULE string"). Ticket
 * 05's answer says "tagged... plus Google event id" without addressing recurrence, so keying
 * [DatesAspectSeeder.FIELD_GOOGLE_EVENT_ID] on the bare event id would silently collapse every
 * occurrence of a recurring event onto one stored row, each import overwriting the last. This file
 * stores `"<eventId>@<occurrenceStartMs>"` in that single field instead - still "the Google event
 * id" in spirit and still one text field per the ticket's schema, just precise enough to identify
 * the OCCURRENCE, matching the precedent [CalendarProvider.updateEventOccurrence] already sets by
 * keying an edit exception on `ORIGINAL_INSTANCE_TIME`. Reasoned engineering call, not something
 * ticket 05/19 answered explicitly - flagged in the build report for review.
 *
 * **Runs on app foreground** (see [com.kevin.legion.MidnightApplication.onCreate]'s call site)
 * **and a manual trigger** - [importNow] is the manual-trigger hook itself, public and
 * context-only, ready for a future settings-screen button or voice tool to call; ticket 19 asked
 * for the mechanism, not a specific UI surface, and `ui/` has no calendar-settings screen to attach
 * a button to yet (CLAUDE.md's "STOP and surface" instruction is about a MISSING design language,
 * not a missing button on an existing screen - there is neither here, so this is noted as an open
 * follow-up rather than a blocking fork).
 */
object CalendarImportController {
    /** How far back a re-import still reconciles - long enough to catch an event someone else
     * moved or cancelled shortly after it happened, short enough that this stays a bounded query. */
    const val WINDOW_PAST_MS = 30L * 24 * 60 * 60 * 1000L

    /** How far forward a re-import looks - roughly half a year, long enough for "what's on my
     * calendar this quarter" to be real, bounded so this never becomes an unbounded table scan. */
    const val WINDOW_FUTURE_MS = 180L * 24 * 60 * 60 * 1000L

    sealed class ImportOutcome {
        data class Imported(val created: Int, val updated: Int, val deleted: Int) : ImportOutcome()

        /** READ_CALENDAR refused - unreadable, never rendered as "nothing to import" (the
         * unreadable-vs-empty rule, CLAUDE.md sec 1). */
        object PermissionMissing : ImportOutcome()
    }

    /**
     * Reads Google's calendars in `[now - WINDOW_PAST_MS, now + WINDOW_FUTURE_MS]`, upserts every
     * occurrence into the Dates aspect keyed on the composite id above, and trashes (via
     * [RecordStore.delete] - the engine's ordinary 30-day-restorable trash, never a hard delete)
     * any previously-imported `source=google` row in that same window whose key the fresh read no
     * longer returned. **Never touches a `source=legion` row** - every read/write below filters to
     * `source == SOURCE_GOOGLE` before it looks at a record at all.
     */
    suspend fun importNow(context: Context, now: Long = System.currentTimeMillis()): ImportOutcome {
        if (!CalendarProvider.hasReadPermission(context)) return ImportOutcome.PermissionMissing

        val db = CarDatabase.getDatabase(context)
        val schema = DatesAspectSeeder.ensureSeeded(context)
        val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())

        val fromMs = now - WINDOW_PAST_MS
        val toMs = now + WINDOW_FUTURE_MS
        val googleEvents = CalendarProvider.eventsInWindow(context, fromMs, toMs)

        val titleFieldId = schema.fieldIds.getValue(DatesAspectSeeder.FIELD_TITLE)
        val startFieldId = schema.fieldIds.getValue(DatesAspectSeeder.FIELD_START)
        val endFieldId = schema.fieldIds.getValue(DatesAspectSeeder.FIELD_END)
        val sourceFieldId = schema.fieldIds.getValue(DatesAspectSeeder.FIELD_SOURCE)
        val googleIdFieldId = schema.fieldIds.getValue(DatesAspectSeeder.FIELD_GOOGLE_EVENT_ID)

        fun compositeKey(eventId: Long, startMs: Long) = "$eventId@$startMs"

        // Only google-sourced rows whose OWN start already falls in this run's window are
        // candidates for the deletion mirror below - a row imported by an earlier, differently
        // windowed run and now outside this window must never be mistaken for "gone from Google"
        // just because this particular query did not re-fetch it.
        val existingGoogleInWindow: Map<String, EngineRecord> = db.engineRecordDao()
            .activeByRecordType(schema.recordTypeId)
            .mapNotNull { record ->
                val payload = JSONObject(record.payload)
                val source = PayloadCodec.readString(payload, sourceFieldId)
                val start = PayloadCodec.readLong(payload, startFieldId)
                val googleId = PayloadCodec.readString(payload, googleIdFieldId)
                if (source == DatesAspectSeeder.SOURCE_GOOGLE && start != null && start in fromMs..toMs && !googleId.isNullOrBlank()) {
                    googleId to record
                } else {
                    null
                }
            }
            .toMap()

        var created = 0
        var updated = 0
        val seenKeys = mutableSetOf<String>()

        for (event in googleEvents) {
            val key = compositeKey(event.eventId, event.startMs)
            seenKeys += key
            val fieldValues = mapOf(
                titleFieldId to event.title,
                startFieldId to event.startMs,
                endFieldId to event.endMs,
                sourceFieldId to DatesAspectSeeder.SOURCE_GOOGLE,
                googleIdFieldId to key,
            )
            val existing = existingGoogleInWindow[key]
            val result = if (existing == null) {
                store.create(schema.recordTypeId, fieldValues, RecordProvenance.DETERMINISTIC, now)
            } else {
                store.update(existing.id, fieldValues, now)
            }
            when (result) {
                is RecordStore.WriteResult.Success -> if (existing == null) created++ else updated++
                is RecordStore.WriteResult.Failure -> { /* left un-imported this pass; next run retries the same key */ }
            }
        }

        var deleted = 0
        for ((key, record) in existingGoogleInWindow) {
            if (key in seenKeys) continue
            if (store.delete(record.id, now) is RecordStore.DeleteResult.Trashed) deleted++
        }

        return ImportOutcome.Imported(created, updated, deleted)
    }
}
