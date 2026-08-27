package com.kevin.legion.calendar

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
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
 * **Widened per `.scratch/backend-erp/issues/01-what-the-backend-owns.md` ruling 11 / ticket 05
 * ruling 3, and this is the gate the Google removal is blocked on.** The old version of this file
 * persisted only title/start/end/source/googleEventId over a fixed `[now-30d, now+180d]` window,
 * silently dropping `description`, `location`, and `allDay` - and the `LEGION::v1` block inside
 * `description` (class-schedule metadata: `course`, `source: canvas_verified`, `conflict`,
 * `status`) is AUTHORED IN Google Calendar and lives nowhere else. Cutting Google before this
 * widened, verified, and run unbounded would delete that metadata permanently, which is exactly
 * what ruling 11 forbids. This file now also imports `location`/`notes` (the prose half of
 * `description`)/`allDay`/`structuredMeta` (the `LEGION::v1` block's machine half, as its own
 * JSON field - see [DatesAspectSeeder.FIELD_STRUCTURED_META]'s doc comment for why a per-key
 * column is not expressible yet), and supports an [importNow] `unbounded` mode alongside the
 * existing windowed default.
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
 * **Runs on app foreground** (see [com.kevin.legion.MidnightApplication.onCreate]'s call site,
 * unchanged - it still calls the windowed default, not [importNow]'s `unbounded` mode; the
 * unbounded run is the one-time verification pass ruling 11 asks for, not every-launch behaviour)
 * **and a manual trigger** - [importNow] is the manual-trigger hook itself, public and
 * context-only, ready for a future settings-screen button or voice tool to call; ticket 19 asked
 * for the mechanism, not a specific UI surface, and `ui/` has no calendar-settings screen to attach
 * a button to yet (CLAUDE.md's "STOP and surface" instruction is about a MISSING design language,
 * not a missing button on an existing screen - there is neither here, so this is noted as an open
 * follow-up rather than a blocking fork).
 */
object CalendarImportController {
    /** How far back a windowed re-import still reconciles - long enough to catch an event someone
     * else moved or cancelled shortly after it happened, short enough that this stays a bounded
     * query. */
    const val WINDOW_PAST_MS = 30L * 24 * 60 * 60 * 1000L

    /** How far forward a windowed re-import looks - roughly half a year, long enough for "what's
     * on my calendar this quarter" to be real, bounded so this never becomes an unbounded table
     * scan. */
    const val WINDOW_FUTURE_MS = 180L * 24 * 60 * 60 * 1000L

    /** How far back and forward [importNow]'s `unbounded` mode reaches from `now`. Not literally
     * infinite - `CalendarContract.Instances`' time-range query requires an actual begin/end pair
     * in milliseconds, and there is no millis value that means "no bound" to that column. 50 years
     * each direction comfortably covers every event on a real Google account (Google Calendar did
     * not exist 50 years ago, and nobody is scheduling 50 years out), so "unbounded" here means
     * "wide enough that nothing real falls outside it", not "mathematically infinite" - stated so
     * this is not mistaken for a stronger guarantee than it is. */
    private const val UNBOUNDED_SPAN_MS = 50L * 365 * 24 * 60 * 60 * 1000L

    /** The slice width an unbounded run reads and writes at a time. [CalendarProvider.eventsInWindow]
     * fully materializes its `Cursor` into a `List` (traced: it is a plain `?.use { c -> ... }` loop
     * with no `Bundle`-based `QUERY_ARG_LIMIT`/`QUERY_ARG_OFFSET` wired in, and `CalendarContract`
     * documents no paging contract for `Instances` that this file could opt into instead), so the
     * only lever available here without changing that file is to keep each individual query's own
     * range small and process one slice at a time rather than one `[now-50y, now+50y]` call - a
     * multi-decade span with a genuinely daily recurring event could otherwise hand back tens of
     * thousands of rows in one `List` all at once. A year at a time keeps each slice's result set to
     * "a normal calendar's worth of events" regardless of how wide the overall run is. */
    private const val UNBOUNDED_CHUNK_MS = 365L * 24 * 60 * 60 * 1000L

    sealed class ImportOutcome {
        data class Imported(val created: Int, val updated: Int, val deleted: Int) : ImportOutcome()

        /** READ_CALENDAR refused - unreadable, never rendered as "nothing to import" (the
         * unreadable-vs-empty rule, CLAUDE.md sec 1). */
        object PermissionMissing : ImportOutcome()
    }

    /** The Dates-schema field ids [buildFieldValues]/[loadExistingGoogle] need, resolved once per
     * [importNow] call rather than re-looked-up per event or per DB scan. Plain data, not a second
     * schema - [DatesAspectSeeder.Schema] stays the source of truth this is built from. */
    internal data class FieldIds(
        val title: Long,
        val start: Long,
        val end: Long,
        val source: Long,
        val googleId: Long,
        val location: Long,
        val notes: Long,
        val allDay: Long,
        val structuredMeta: Long,
    ) {
        companion object {
            fun from(schema: DatesAspectSeeder.Schema) = FieldIds(
                title = schema.fieldIds.getValue(DatesAspectSeeder.FIELD_TITLE),
                start = schema.fieldIds.getValue(DatesAspectSeeder.FIELD_START),
                end = schema.fieldIds.getValue(DatesAspectSeeder.FIELD_END),
                source = schema.fieldIds.getValue(DatesAspectSeeder.FIELD_SOURCE),
                googleId = schema.fieldIds.getValue(DatesAspectSeeder.FIELD_GOOGLE_EVENT_ID),
                location = schema.fieldIds.getValue(DatesAspectSeeder.FIELD_LOCATION),
                notes = schema.fieldIds.getValue(DatesAspectSeeder.FIELD_NOTES),
                allDay = schema.fieldIds.getValue(DatesAspectSeeder.FIELD_ALL_DAY),
                structuredMeta = schema.fieldIds.getValue(DatesAspectSeeder.FIELD_STRUCTURED_META),
            )
        }
    }

    private fun compositeKey(eventId: Long, startMs: Long) = "$eventId@$startMs"

    /**
     * Maps one Google occurrence into the field-value map [RecordStore.create]/[RecordStore.update]
     * expect - the pure half of the import, deliberately free of any DB/`ContentResolver` call so it
     * can be exercised directly in a unit test even though [CalendarProvider.eventsInWindow] itself
     * cannot be under Robolectric (this file's own test file's class doc: no real backing
     * `ContentProvider` for the calendar authority in this project).
     *
     * [CalendarReadToolLogic.structuredBlock]'s established discipline - machine fields reach the
     * model, prose does not - is reused here rather than re-parsed: a `LEGION::v1` block becomes
     * [FieldIds.structuredMeta] (its own JSON-encoded field, no longer living only inside the raw
     * Google description string), and [CalendarReadToolLogic.proseAfter] returns the words a human
     * actually wrote, which land in [FieldIds.notes]. An event with neither writes `null` to both -
     * never an empty string standing in for "there was nothing to read" (CLAUDE.md sec 4 rule 5).
     * [FieldIds.location] gets the same treatment: Google hands back `""` for an unset
     * `EVENT_LOCATION` ([CalendarProvider.GoogleCalendarEvent.location]'s own doc comment), and that
     * is blank-mapped to `null` here rather than stored as a location literally called "".
     * [FieldIds.allDay] is never null - every `Instances` row states its own `ALL_DAY` bit, so this
     * is always a real observed fact, not an estimate.
     */
    internal fun buildFieldValues(event: CalendarProvider.GoogleCalendarEvent, fieldIds: FieldIds): Map<Long, Any?> {
        val key = compositeKey(event.eventId, event.startMs)
        val meta = CalendarReadToolLogic.structuredBlock(event.description)
        val prose = CalendarReadToolLogic.proseAfter(event.description)
        return mapOf(
            fieldIds.title to event.title,
            fieldIds.start to event.startMs,
            fieldIds.end to event.endMs,
            fieldIds.source to DatesAspectSeeder.SOURCE_GOOGLE,
            fieldIds.googleId to key,
            fieldIds.location to event.location.trim().ifBlank { null },
            fieldIds.notes to prose,
            fieldIds.allDay to event.allDay,
            fieldIds.structuredMeta to meta?.let { JSONObject(it).toString() },
        )
    }

    /**
     * Every `source=google` record currently active in the Dates record type, keyed by its stored
     * [FieldIds.googleId], filtered to [inScope] on the record's OWN [FieldIds.start] - the
     * windowed/unbounded split lives entirely in what [inScope] is, everything else about this scan
     * is identical either way. Returns ids (not whole records) because the only thing either caller
     * needs afterward is [RecordStore.delete]'s target id.
     */
    private suspend fun loadExistingGoogle(
        db: CarDatabase,
        schema: DatesAspectSeeder.Schema,
        fieldIds: FieldIds,
        inScope: (startMs: Long) -> Boolean,
    ): MutableMap<String, Long> =
        db.engineRecordDao()
            .activeByRecordType(schema.recordTypeId)
            .mapNotNull { record ->
                val payload = JSONObject(record.payload)
                val source = PayloadCodec.readString(payload, fieldIds.source)
                val start = PayloadCodec.readLong(payload, fieldIds.start)
                val googleId = PayloadCodec.readString(payload, fieldIds.googleId)
                if (source == DatesAspectSeeder.SOURCE_GOOGLE && start != null && inScope(start) && !googleId.isNullOrBlank()) {
                    googleId to record.id
                } else {
                    null
                }
            }
            .toMap()
            .toMutableMap()

    /**
     * Upserts one page of Google events against [known] (pre-existing DB rows plus anything a
     * PRIOR call in the same run already created - see the call site in [importUnbounded] for why
     * that mutation matters), returning created/updated counts and the composite keys this page
     * touched so the caller can fold them into a run-wide seen-set.
     */
    private suspend fun upsertAll(
        store: RecordStore,
        recordTypeId: Long,
        fieldIds: FieldIds,
        events: List<CalendarProvider.GoogleCalendarEvent>,
        known: MutableMap<String, Long>,
        now: Long,
    ): Triple<Int, Int, Set<String>> {
        var created = 0
        var updated = 0
        val seenKeys = mutableSetOf<String>()
        for (event in events) {
            val key = compositeKey(event.eventId, event.startMs)
            seenKeys += key
            val fieldValues = buildFieldValues(event, fieldIds)
            val existingId = known[key]
            val result = if (existingId == null) {
                store.create(recordTypeId, fieldValues, RecordProvenance.DETERMINISTIC, now)
            } else {
                store.update(existingId, fieldValues, now)
            }
            when (result) {
                is RecordStore.WriteResult.Success -> {
                    if (existingId == null) {
                        created++
                        // Recorded immediately, not just counted: an unbounded run's chunk
                        // boundaries can hand back the SAME occurrence twice (a multi-day event
                        // whose span crosses a chunk edge sits inside both adjacent [fromMs, toMs)
                        // queries). Without this, the second sighting would create a duplicate row
                        // instead of recognizing the one this loop just made and updating it.
                        known[key] = result.recordId
                    } else {
                        updated++
                    }
                }
                is RecordStore.WriteResult.Failure -> {
                    // left un-imported this pass; next run retries the same key
                }
            }
        }
        return Triple(created, updated, seenKeys)
    }

    /**
     * Reads `[now - WINDOW_PAST_MS, now + WINDOW_FUTURE_MS]`, upserts every occurrence into the
     * Dates aspect keyed on the composite id above, and trashes (via [RecordStore.delete] - the
     * engine's ordinary 30-day-restorable trash, never a hard delete) any previously-imported
     * `source=google` row in that same window whose key the fresh read no longer returned.
     * **Never touches a `source=legion` row or a google row outside this window** - only a google
     * row whose OWN start already falls in `[fromMs, toMs]` is even a deletion candidate: a row
     * imported by an earlier, differently windowed run and now outside this window must never be
     * mistaken for "gone from Google" just because this particular query did not re-fetch it.
     */
    private suspend fun importWindowed(
        context: Context,
        db: CarDatabase,
        store: RecordStore,
        schema: DatesAspectSeeder.Schema,
        fieldIds: FieldIds,
        now: Long,
    ): ImportOutcome.Imported {
        val fromMs = now - WINDOW_PAST_MS
        val toMs = now + WINDOW_FUTURE_MS
        val googleEvents = CalendarProvider.eventsInWindow(context, fromMs, toMs)

        val existingGoogleInWindow = loadExistingGoogle(db, schema, fieldIds) { start -> start in fromMs..toMs }
        val (created, updated, seenKeys) =
            upsertAll(store, schema.recordTypeId, fieldIds, googleEvents, existingGoogleInWindow, now)

        var deleted = 0
        for ((key, id) in existingGoogleInWindow) {
            if (key in seenKeys) continue
            if (store.delete(id, now) is RecordStore.DeleteResult.Trashed) deleted++
        }
        return ImportOutcome.Imported(created, updated, deleted)
    }

    /**
     * The unbounded run `.scratch/backend-erp/issues/01-what-the-backend-owns.md` ruling 11 asks
     * for before Google can be cut: reads roughly `[now - 50y, now + 50y]` (see
     * [UNBOUNDED_SPAN_MS]) in year-wide slices (see [UNBOUNDED_CHUNK_MS]) rather than one call, so
     * at most one slice's event list is ever resident in memory at once.
     *
     * **What "a google row that disappeared" means here, concluded rather than assumed.** The
     * windowed run's deletion guard (`start in fromMs..toMs`) exists ONLY because a windowed run's
     * own query is narrower than the set of rows a PRIOR, differently-windowed run could have
     * created - a row from 2 years ago that a 30-day-past window does not re-fetch is not "gone
     * from Google", it is simply outside this run's question. An unbounded run's query has no such
     * gap: [UNBOUNDED_SPAN_MS] is wider than any window this app has ever queried with (windowed
     * runs reach at most 30 days back / 180 forward), so **every** `source=google` row already in
     * the database falls inside this run's own scope by construction. That means the windowed
     * guard would be vacuously true here anyway - but reusing it regardless would be reusing
     * WINDOWED SEMANTICS built around a gap that does not exist in this mode, which is precisely
     * the mistake the task called out to avoid, so this path calls [loadExistingGoogle] with an
     * unconditional `{ true }` scope instead of threading `fromMs`/`toMs` through at all. The
     * practical conclusion: for an unbounded run, a `source=google` row this pass does not see
     * again really is gone from Google (deleted, or its calendar left the device's `com.google`
     * account set entirely), and gets trashed exactly as the windowed path trashes one inside its
     * narrower scope.
     */
    private suspend fun importUnbounded(
        context: Context,
        db: CarDatabase,
        store: RecordStore,
        schema: DatesAspectSeeder.Schema,
        fieldIds: FieldIds,
        now: Long,
    ): ImportOutcome.Imported {
        val existingGoogleAll = loadExistingGoogle(db, schema, fieldIds) { true }

        var created = 0
        var updated = 0
        val seenKeys = mutableSetOf<String>()

        var chunkStart = now - UNBOUNDED_SPAN_MS
        val end = now + UNBOUNDED_SPAN_MS
        while (chunkStart < end) {
            val chunkEnd = minOf(chunkStart + UNBOUNDED_CHUNK_MS, end)
            val events = CalendarProvider.eventsInWindow(context, chunkStart, chunkEnd)
            val (c, u, keys) = upsertAll(store, schema.recordTypeId, fieldIds, events, existingGoogleAll, now)
            created += c
            updated += u
            seenKeys += keys
            chunkStart = chunkEnd
        }

        var deleted = 0
        for ((key, id) in existingGoogleAll) {
            if (key in seenKeys) continue
            if (store.delete(id, now) is RecordStore.DeleteResult.Trashed) deleted++
        }
        return ImportOutcome.Imported(created, updated, deleted)
    }

    /**
     * Entry point - unchanged default (windowed) plus the new `unbounded` mode, per the task's own
     * "add an unbounded mode rather than changing the default": [com.kevin.legion.MidnightApplication]'s
     * foreground trigger calls this with no arguments and must keep getting the same bounded,
     * cheap-per-launch behaviour it always has. `unbounded = true` is for the one-time verification
     * run ruling 11 requires before Google Calendar is removed.
     */
    suspend fun importNow(context: Context, now: Long = System.currentTimeMillis(), unbounded: Boolean = false): ImportOutcome {
        if (!CalendarProvider.hasReadPermission(context)) return ImportOutcome.PermissionMissing

        val db = CarDatabase.getDatabase(context)
        val schema = DatesAspectSeeder.ensureSeeded(context)
        val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        val fieldIds = FieldIds.from(schema)

        return if (unbounded) {
            importUnbounded(context, db, store, schema, fieldIds, now)
        } else {
            importWindowed(context, db, store, schema, fieldIds, now)
        }
    }
}
