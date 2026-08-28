package com.kevin.legion.calendar

import android.content.Context
import android.util.Log
import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.engine.dates.DatesAspectSeeder
import org.json.JSONObject
import java.util.UUID

/**
 * Google Calendar import as a one-way feed into the Dates half of the merged `events` table
 * (`.scratch/aspect-engine/issues/19-build-dates-aspect.md` point 2, locked at
 * `.scratch/aspect-engine/issues/05-central-date-database.md` answer point 2/3: "store everything
 * imported, invites included... tagged source=google plus event id; re-import updates in place;
 * Google-side deletions mirror; one-way in v1"). Reuses [CalendarProvider.eventsInWindow] verbatim
 * - the same query `ui/TodayScreen.kt`/`ui/NotesScreen.kt`/the sitrep already read, never a second
 * one.
 *
 * **Repointed off the engine entirely (backend-erp ticket 17, "RULED 2026-08-28": Dates repoints
 * onto the SAME `events` table Notes already uses).** Before this repoint this file wrote through
 * [com.kevin.legion.engine.RecordStore] into the engine's Dates aspect; that write funnel is gone.
 * This is now the same class of writer [com.kevin.legion.notes.NotesController]'s own unconfigured
 * branch already is - a direct [com.kevin.legion.data.local.EventDao] read/write, no engine, no
 * `PayloadCodec`, no `FieldDef` lookups. **Unlike [com.kevin.legion.notes.NotesController], there is
 * no configured-vs-unconfigured branch here at all, and that is a deliberate, reasoned choice, not
 * an oversight**: a Notes `Item` is a LEGION-native creation with no independent copy anywhere else,
 * so it needs Supabase to reach a second device; a Google-imported event is already synced
 * cross-device by Google itself - each phone independently reads the SAME Google account's calendar
 * and imports its own local copy - so writing straight to the local `events` table, unconditionally,
 * preserves the exact behaviour this file already had (purely local, no Supabase involvement,
 * regardless of whether a Supabase project is configured) rather than inventing a new sync path
 * ticket 17 never asked for. See this ticket's own build report for the fuller argument, including
 * why re-running [com.kevin.legion.backend.EventsReconcile]'s one-time Dates migration after this
 * repoint would NOT delete a freshly-imported appointment (it never touches rows outside its own
 * server-diffed set).
 *
 * **Deduplication is now keyed on [DatesAspectSeeder.FIELD_GOOGLE_EVENT_ID]'s value directly**
 * (`"<eventId>@<occurrenceStartMs>"`, unchanged composite-key shape - see [compositeKey]'s own doc
 * comment below), scanned against [Event.kind] = [EventKind.APPOINTMENT] rows in the local table,
 * rather than against an engine record type's promoted field. [Event.serverId] is a client-minted
 * placeholder for every row this file creates, exactly the posture
 * [com.kevin.legion.notes.NotesController.addItem]'s own unconfigured branch already established
 * for the identical column - nothing here ever looks a row up by it.
 *
 * **Widened per `.scratch/backend-erp/issues/01-what-the-backend-owns.md` ruling 11 / ticket 05
 * ruling 3, and this is the gate the Google removal is blocked on.** This file imports
 * `location`/`notes` (the prose half of `description`)/`allDay`/`structuredMeta` (the `LEGION::v1`
 * block's machine half, its own field since [com.kevin.legion.data.local.MIGRATION_47_48] - see
 * that migration's own doc comment for why an unread Room column stopped being an acceptable place
 * to leave it once this file stopped routing through the engine/server at all), and supports an
 * [importNow] `unbounded` mode alongside the existing windowed default.
 *
 * **The composite key.** [CalendarProvider.GoogleCalendarEvent.eventId] is the PARENT event's id -
 * every occurrence of a recurring series shares the same one (`CalendarProvider`'s own class doc:
 * "a repeating Google event contributes one row per occurrence... never a raw RRULE string"). Ticket
 * 05's answer says "tagged... plus Google event id" without addressing recurrence, so keying
 * [Event.googleEventId] on the bare event id would silently collapse every occurrence of a
 * recurring event onto one stored row, each import overwriting the last. This file stores
 * `"<eventId>@<occurrenceStartMs>"` in that single field instead - still "the Google event id" in
 * spirit and still one column, just precise enough to identify the OCCURRENCE, matching the
 * precedent [CalendarProvider.updateEventOccurrence] already sets by keying an edit exception on
 * `ORIGINAL_INSTANCE_TIME`. Reasoned engineering call, not something ticket 05/19 answered
 * explicitly - flagged in the build report for review (unchanged from before this repoint).
 *
 * **A write failure is worded, not thrown** (CLAUDE.md's feature-add checklist) - this file runs on
 * every app foreground, not behind a voice tool's own catch-all, so [buildEventRow]'s Room write is
 * wrapped per-row: one row failing to write is logged and left for the NEXT run to retry (the same
 * "left un-imported this pass" posture the old engine-backed version already had for a
 * [com.kevin.legion.engine.RecordStore.WriteResult.Failure]), never a crash that would also abort
 * every other row in the same page.
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
    private const val TAG = "CalendarImportController"

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

    /** [CalendarProvider.GoogleCalendarEvent.eventId] is the PARENT event's own id, shared by every
     * occurrence of a recurring series - see this file's own class doc for why the OCCURRENCE's
     * start time is folded in too. */
    private fun compositeKey(eventId: Long, startMs: Long) = "$eventId@$startMs"

    /**
     * Maps one Google occurrence into the [Event] row [db]'s write should apply - the pure half of
     * the import, deliberately free of any DB/`ContentResolver` call so it can be exercised directly
     * in a unit test even though [CalendarProvider.eventsInWindow] itself cannot be under
     * Robolectric (this file's own test file's class doc: no real backing `ContentProvider` for the
     * calendar authority in this project).
     *
     * [existing] is the row this occurrence already has in the local table, or null on a first
     * import - when non-null, [Event.id]/[Event.serverId]/[Event.createdAt] are preserved by
     * `copy`, matching [com.kevin.legion.notes.NotesController.toEventRow]'s own "copy the row you
     * already have forward, never invent columns you don't own" posture, adapted for a writer with
     * no upstream [com.kevin.legion.data.local.ListItem] to read those three from at all - a fresh
     * row instead mints [Event.serverId] as a client-minted placeholder UUID, the same posture
     * [Event]'s own class doc already establishes for the unconfigured write path in general.
     *
     * [CalendarReadToolLogic.structuredBlock]'s established discipline - machine fields reach the
     * model, prose does not - is reused here rather than re-parsed: a `LEGION::v1` block becomes
     * [Event.structuredMeta] (its own JSON-encoded column since [com.kevin.legion.data.local.MIGRATION_47_48]),
     * and [CalendarReadToolLogic.proseAfter] returns the words a human actually wrote, which land in
     * [Event.notes]. An event with neither writes `null` to both - never an empty string standing
     * in for "there was nothing to read" (CLAUDE.md section 4 rule 5). [Event.location] gets the
     * same treatment: Google hands back `""` for an unset `EVENT_LOCATION`
     * ([CalendarProvider.GoogleCalendarEvent.location]'s own doc comment), and that is blank-mapped
     * to `null` here rather than stored as a location literally called "". [Event.allDay] is never
     * ambiguous - every `Instances` row states its own `ALL_DAY` bit, so this is always a real
     * observed fact, not an estimate.
     *
     * [newId] is the id a genuinely NEW row must be explicitly inserted at - [Event.APPOINTMENT_ID_BASE]'s
     * own doc comment for why this can never be left at `0` (natural autoincrement) for an
     * appointment. Ignored entirely when [existing] is non-null (an update keeps its own id via
     * `copy`, never this parameter).
     */
    internal fun buildEventRow(event: CalendarProvider.GoogleCalendarEvent, existing: Event?, now: Long, newId: Long = 0L): Event {
        val key = compositeKey(event.eventId, event.startMs)
        val meta = CalendarReadToolLogic.structuredBlock(event.description)
        val prose = CalendarReadToolLogic.proseAfter(event.description)
        val base = existing ?: Event(
            id = newId,
            serverId = UUID.randomUUID().toString(),
            // A fresh, immutable local identity - see Event.guid's own doc comment for why this
            // cannot be serverId (which EventsReconcile overwrites with the server's real uuid) and
            // why EventsReconcile.run's Dates branch depends on this value never changing once
            // minted (backend-erp ticket 17's coordinator follow-up, 2026-08-28).
            guid = UUID.randomUUID().toString(),
            title = event.title,
            startsAt = event.startMs,
            source = DatesAspectSeeder.SOURCE_GOOGLE,
            kind = EventKind.APPOINTMENT,
            googleEventId = key,
            updatedAtMs = now,
            createdAt = now,
        )
        return base.copy(
            title = event.title,
            startsAt = event.startMs,
            endsAt = event.endMs,
            allDay = event.allDay,
            location = event.location.trim().ifBlank { null },
            notes = prose,
            structuredMeta = meta?.let { JSONObject(it).toString() },
            source = DatesAspectSeeder.SOURCE_GOOGLE,
            kind = EventKind.APPOINTMENT,
            googleEventId = key,
            updatedAtMs = now,
            deleted = false,
        )
    }

    /**
     * Every `source=google`, `kind=appointment` row currently active in the local `events` table,
     * keyed by its stored [Event.googleEventId], filtered to [inScope] on the row's OWN
     * [Event.startsAt] - the windowed/unbounded split lives entirely in what [inScope] is,
     * everything else about this scan is identical either way. Returns full rows (not just ids)
     * because [buildEventRow] needs [Event.id]/[Event.serverId]/[Event.createdAt] to copy forward
     * on an update, and Google-side-deletion handling needs the whole row to flip [Event.deleted] on.
     */
    private suspend fun loadExistingGoogle(
        db: CarDatabase,
        inScope: (startMs: Long) -> Boolean,
    ): MutableMap<String, Event> =
        db.eventDao().getActiveByKind(EventKind.APPOINTMENT)
            .filter { row ->
                row.source == DatesAspectSeeder.SOURCE_GOOGLE && !row.googleEventId.isNullOrBlank() &&
                    row.startsAt != null && inScope(row.startsAt)
            }
            .associateBy { it.googleEventId!! }
            .toMutableMap()

    /**
     * The next id a genuinely NEW appointment row must be explicitly inserted at -
     * [Event.APPOINTMENT_ID_BASE]'s own doc comment states the property this exists to serve.
     * Reads the table's current appointment high-water mark fresh (via
     * [com.kevin.legion.data.local.EventDao.maxIdAtOrAbove]) rather than caching one across
     * [upsertAll] calls - simple over clever at this app's scale (a personal calendar import, not a
     * high-throughput writer), and correct regardless of how many appointments a chunk creates:
     * each call sees the REAL result of every insert [upsertAll]'s own loop already performed,
     * since [nextAppointmentId] is called once per NEW row, immediately before that row is written.
     * `internal` (not `private`) specifically so the property this allocator establishes can be
     * tested directly against a real Robolectric Room database, not just inferred from
     * [buildEventRow]'s pure half.
     */
    internal suspend fun nextAppointmentId(db: CarDatabase): Long {
        val current = db.eventDao().maxIdAtOrAbove(Event.APPOINTMENT_ID_BASE)
        return (current ?: (Event.APPOINTMENT_ID_BASE - 1)) + 1
    }

    /**
     * Upserts one page of Google events against [known] (pre-existing DB rows plus anything a
     * PRIOR call in the same run already created - see the call site in [importUnbounded] for why
     * that mutation matters), returning created/updated counts and the composite keys this page
     * touched so the caller can fold them into a run-wide seen-set. Each row's own write is
     * wrapped so one failure never aborts the rest of the page - see this file's own class doc for
     * why a thrown exception is wrong here.
     */
    private suspend fun upsertAll(
        db: CarDatabase,
        events: List<CalendarProvider.GoogleCalendarEvent>,
        known: MutableMap<String, Event>,
        now: Long,
    ): Triple<Int, Int, Set<String>> {
        var created = 0
        var updated = 0
        val seenKeys = mutableSetOf<String>()
        for (event in events) {
            val key = compositeKey(event.eventId, event.startMs)
            seenKeys += key
            val existing = known[key]
            // The allocator is only ever consulted for a genuinely NEW row - an update keeps its
            // own existing id via buildEventRow's own `copy`, never touching the disjoint range at
            // all (it was already seated there when it was first created).
            val row = if (existing == null) {
                buildEventRow(event, existing = null, now, newId = nextAppointmentId(db))
            } else {
                buildEventRow(event, existing, now)
            }
            try {
                if (existing == null) {
                    val id = db.eventDao().insert(row)
                    created++
                    // Recorded immediately, not just counted: an unbounded run's chunk
                    // boundaries can hand back the SAME occurrence twice (a multi-day event
                    // whose span crosses a chunk edge sits inside both adjacent [fromMs, toMs)
                    // queries). Without this, the second sighting would create a duplicate row
                    // instead of recognizing the one this loop just made and updating it.
                    known[key] = row.copy(id = id)
                } else {
                    db.eventDao().update(row)
                    updated++
                }
            } catch (e: Exception) {
                // left un-imported this pass; next run retries the same key - the direct-Room
                // equivalent of the old RecordStore.WriteResult.Failure branch.
                Log.w(TAG, "import write failed for $key: ${e.message}")
            }
        }
        return Triple(created, updated, seenKeys)
    }

    /**
     * Reads `[now - WINDOW_PAST_MS, now + WINDOW_FUTURE_MS]`, upserts every occurrence into the
     * local `events` table keyed on the composite id above, and marks [Event.deleted] true (never a
     * hard delete - matching [com.kevin.legion.notes.NotesController.removeItem]'s own unconfigured
     * posture for this exact table) on any previously-imported `source=google` row in that same
     * window whose key the fresh read no longer returned.
     * **Never touches a `source=legion` row, a `kind=reminder` row, or a google row outside this
     * window** - only a google row whose OWN start already falls in `[fromMs, toMs]` is even a
     * deletion candidate: a row imported by an earlier, differently windowed run and now outside
     * this window must never be mistaken for "gone from Google" just because this particular query
     * did not re-fetch it.
     */
    private suspend fun importWindowed(context: Context, db: CarDatabase, now: Long): ImportOutcome.Imported {
        val fromMs = now - WINDOW_PAST_MS
        val toMs = now + WINDOW_FUTURE_MS
        val googleEvents = CalendarProvider.eventsInWindow(context, fromMs, toMs)

        val existingGoogleInWindow = loadExistingGoogle(db) { start -> start in fromMs..toMs }
        val (created, updated, seenKeys) = upsertAll(db, googleEvents, existingGoogleInWindow, now)

        var deleted = 0
        for ((key, row) in existingGoogleInWindow) {
            if (key in seenKeys) continue
            try {
                db.eventDao().update(row.copy(deleted = true, updatedAtMs = now))
                deleted++
            } catch (e: Exception) {
                Log.w(TAG, "trash write failed for $key: ${e.message}")
            }
        }
        return ImportOutcome.Imported(created, updated, deleted)
    }

    /**
     * The unbounded run `.scratch/backend-erp/issues/01-what-the-backend-owns.md` ruling 11 asks
     * for before Google can be cut: reads roughly `[now - 50y, now + 50y]` (see
     * [UNBOUNDED_SPAN_MS]) in year-wide slices (see [UNBOUNDED_CHUNK_MS]) rather than one call, so
     * at most one slice's event list is ever resident in memory at once.
     *
     * **What "a google row that disappeared" means here, concluded rather than assumed** (unchanged
     * reasoning from before this repoint - only the storage underneath moved). [UNBOUNDED_SPAN_MS]
     * is wider than any window this app has ever queried with (windowed runs reach at most 30 days
     * back / 180 forward), so every `source=google` row already in the local table falls inside
     * this run's own scope by construction, and a row this pass does not see again really is gone
     * from Google - deleted, or its calendar left the device's `com.google` account set entirely -
     * and gets marked [Event.deleted] exactly as the windowed path marks one inside its narrower
     * scope. This path calls [loadExistingGoogle] with an unconditional `{ true }` scope rather than
     * threading `fromMs`/`toMs` through, for the identical reason the pre-repoint version did.
     */
    private suspend fun importUnbounded(context: Context, db: CarDatabase, now: Long): ImportOutcome.Imported {
        val existingGoogleAll = loadExistingGoogle(db) { true }

        var created = 0
        var updated = 0
        val seenKeys = mutableSetOf<String>()

        var chunkStart = now - UNBOUNDED_SPAN_MS
        val end = now + UNBOUNDED_SPAN_MS
        while (chunkStart < end) {
            val chunkEnd = minOf(chunkStart + UNBOUNDED_CHUNK_MS, end)
            val events = CalendarProvider.eventsInWindow(context, chunkStart, chunkEnd)
            val (c, u, keys) = upsertAll(db, events, existingGoogleAll, now)
            created += c
            updated += u
            seenKeys += keys
            chunkStart = chunkEnd
        }

        var deleted = 0
        for ((key, row) in existingGoogleAll) {
            if (key in seenKeys) continue
            try {
                db.eventDao().update(row.copy(deleted = true, updatedAtMs = now))
                deleted++
            } catch (e: Exception) {
                Log.w(TAG, "trash write failed for $key: ${e.message}")
            }
        }
        return ImportOutcome.Imported(created, updated, deleted)
    }

    /**
     * Entry point - unchanged default (windowed) plus the `unbounded` mode.
     * [com.kevin.legion.MidnightApplication]'s foreground trigger calls this with no arguments and
     * must keep getting the same bounded, cheap-per-launch behaviour it always has. `unbounded =
     * true` is for the one-time verification run ruling 11 requires before Google Calendar is
     * removed. No engine schema to resolve any more - the only setup left is the Room database
     * itself.
     */
    suspend fun importNow(context: Context, now: Long = System.currentTimeMillis(), unbounded: Boolean = false): ImportOutcome {
        if (!CalendarProvider.hasReadPermission(context)) return ImportOutcome.PermissionMissing

        val db = CarDatabase.getDatabase(context)
        return if (unbounded) {
            importUnbounded(context, db, now)
        } else {
            importWindowed(context, db, now)
        }
    }
}
