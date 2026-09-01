package com.kevin.legion.engine.migration

import android.content.Context
import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.dates.DatesAspectSeeder
import com.kevin.legion.engine.notes.NotesAspectSeeder
import org.json.JSONObject

/**
 * Step 4 of the engine retirement sequence (`.scratch/backend-erp/issues/15-engine-retirement-sequence.md`):
 * the one-time, idempotent copier that reconciles the engine's Notes `Item` AND Dates `Event`
 * records onto the local `events` table BEFORE [com.kevin.legion.notes.NotesController]'s
 * unconfigured read/write path is repointed off [com.kevin.legion.engine.RecordStore]/
 * `engineRecordDao()` and onto [com.kevin.legion.data.local.EventDao]. Mirrors
 * [EnginePlacesRetirementCopy]/[EnginePantryRetirementCopy]'s shape - see those objects' own class
 * docs for the general reasoning ("runs the opposite direction from the wave copiers", "deletes
 * nothing"), repeated here only where Notes+Dates' answer differs.
 *
 * **Identity is by [com.kevin.legion.data.local.EngineRecord.id] itself, not a derived key - and
 * this is the whole point of the step (ticket 15's own "THE ID CONTRACT" section).**
 * `ListItem.id` is an `AlarmManager` `PendingIntent` request code and a soft foreign key from
 * `list_item_skips.itemId`/`workout_set_logs.sourceListItemId`/`muted_reminders.recordId`. Today
 * the unconfigured path hands out `records.id` directly (`NotesController.toListItem`'s `id =
 * record.id`), so the ONLY way this copier can avoid orphaning every armed alarm, skip, mute and
 * workout log on the phone is to seat each engine record at that SAME id in `events` - never a
 * fresh autoincremented one, the way [com.kevin.legion.backend.EventsReconcile]'s carried-id dance
 * (`b17bc88`) derives a REPLICA id from `origin_guid`. There is no indirection to derive through
 * here; the target id already IS the source id.
 *
 * **The occupancy check ([com.kevin.legion.data.local.EventDao.getById]) is the collision guard,
 * matching [com.kevin.legion.data.local.EventDao.upsert]'s own reasoning for why a colliding row
 * is skipped rather than clobbered.** In the ordinary case it never fires: this object's own
 * [copyIfNeeded] is gated by [KEY_COMPLETED] and is the ONLY writer of `events` for an
 * unconfigured install until it has completed once, and [com.kevin.legion.notes.NotesController]
 * calls this gate before touching `events` at all (see that object's own `ensureLegacyReconciled`),
 * so no locally-created row can ever occupy an id before every engine record has had first claim
 * on its own. No two engine records ever collide with EACH OTHER either - `records.id` is a single
 * autoincrement primary key shared across every aspect (ticket 15's own "the shared records id
 * space"), so a Notes `Item` and a Dates `Event` can never mint the same id to begin with.
 *
 * **Reconcile-and-repoint, never blind-switch (ticket 05's rule): this only ever fills gaps.** An
 * id already occupied in `events` is left alone - matching [EnginePlacesRetirementCopy]/
 * [EnginePantryRetirementCopy]'s own "existing wins ties" posture, adapted here to "existing" being
 * impossible under ordinary operation rather than a realistic race to protect against.
 *
 * **`kind` is set from the record type being read, never inferred from shape** - a Notes `Item` is
 * always [EventKind.REMINDER], a Dates `Event` is always [EventKind.EVENT], the identical
 * ruling [com.kevin.legion.backend.EventsReconcile] already applies on the configured-upload path
 * (ticket 11's 2026-08-27 ruling #1).
 *
 * **`serverId` is a client-minted placeholder, never a real server identity** - see [Event]'s own
 * class doc for why a locally-seated, never-uploaded row still needs a syntactically valid, unique
 * value for that NOT NULL indexed column. Nothing on the unconfigured path ever looks a row up by
 * it (see [com.kevin.legion.data.local.EventDao.upsert]'s own doc comment for why the unconfigured
 * write path bypasses that function entirely), so its exact value carries no meaning beyond
 * satisfying the schema.
 *
 * **CORRECTED 2026-08-28 (backend-erp ticket 17): Dates `Event` records DO now have a live
 * unconfigured consumer of the copy this makes.** At the time this copier was first written,
 * `engine/dates/DatesAgenda.kt`/`service/DatesAlarmScheduler.kt`/`calendar/CalendarImportController.kt`
 * all read/wrote the engine unconditionally, with no configured/unconfigured branch to repoint at
 * all - the identical shape ticket 16 found for fleet's `ServiceHistory`/`MaintenanceSchedule` - so
 * this copier's Dates half was pure groundwork for a repoint that had not yet been authorised.
 * Ticket 17 authorised and built that repoint: `DatesAgenda` now reads the local `events` table
 * directly, and `CalendarImportController` writes it directly, no engine involved in either
 * direction any more. This copier's job is UNCHANGED by that - it is still the one-time bridge that
 * seats every pre-repoint engine `Event` record at its own `records.id` in `events`, exactly the
 * shape [DatesAgenda]'s own reads now depend on to see historical data at all (see
 * [com.kevin.legion.engine.dates.DatesAgenda]'s own class doc for why the engine's Dates rows are
 * now EXCLUDED from its cross-aspect scan - this copy is the only path by which a pre-repoint Dates
 * event still reaches the agenda). Confirmed run correctly by `DatesAgendaTest`'s own
 * "an old Dates event still living in the engine is excluded, never double-counted" case, which
 * proves the negative half of the same contract.
 *
 * **Deletes nothing.** The engine's `Item`/`Event` records are read here and never trashed,
 * updated, or touched - ticket 15 is explicit that nothing is deleted until every aspect is
 * repointed and soaked.
 */
object EngineNotesRetirementCopy {
    private const val PREFS = "engine_notes_retirement"
    private const val KEY_COMPLETED = "notes_events_repointed_v1"

    /** [itemsCopied]/[eventsCopied] count only rows actually written this call. [alreadyDone] is
     * true only when the SharedPreferences fast path skipped the pass entirely without even
     * reading the engine. */
    data class Result(val itemsCopied: Int, val eventsCopied: Int, val alreadyDone: Boolean)

    /**
     * Copies every active engine Notes `Item` and Dates `Event` record whose `records.id` has no
     * row at all in `events` into `events`, seated at that SAME id. Idempotent two ways, matching
     * [EnginePlacesRetirementCopy]'s own shape: the [KEY_COMPLETED] flag short-circuits every call
     * after the first successful pass, and even without it a re-run is safe because the per-id
     * occupancy check simply finds nothing left to copy the second time.
     */
    suspend fun copyIfNeeded(context: Context): Result {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_COMPLETED, false)) return Result(itemsCopied = 0, eventsCopied = 0, alreadyDone = true)

        val db = CarDatabase.getDatabase(context)
        val notesSchema = NotesAspectSeeder.ensureSeeded(context)
        val datesSchema = DatesAspectSeeder.ensureSeeded(context)

        var itemsCopied = 0
        for (record in db.engineRecordDao().activeByRecordType(notesSchema.recordTypeId)) {
            if (db.eventDao().getById(record.id) != null) continue // already seated - reconcile, never overwrite

            val payload = JSONObject(record.payload)
            fun s(name: String) = PayloadCodec.readString(payload, notesSchema.fieldIds.getValue(name))
            fun l(name: String) = PayloadCodec.readLong(payload, notesSchema.fieldIds.getValue(name))
            fun i(name: String) = PayloadCodec.readDouble(payload, notesSchema.fieldIds.getValue(name))?.toInt()
            fun b(name: String, default: Boolean = false) = PayloadCodec.readBoolean(payload, notesSchema.fieldIds.getValue(name), default)

            db.eventDao().insert(
                Event(
                    id = record.id,
                    serverId = java.util.UUID.randomUUID().toString(),
                    title = s(NotesAspectSeeder.FIELD_TEXT).orEmpty(),
                    startsAt = l(NotesAspectSeeder.FIELD_STARTS_AT),
                    endsAt = l(NotesAspectSeeder.FIELD_ENDS_AT),
                    allDay = b(NotesAspectSeeder.FIELD_ALL_DAY, default = true),
                    source = DatesAspectSeeder.SOURCE_LEGION,
                    kind = EventKind.REMINDER,
                    done = b(NotesAspectSeeder.FIELD_DONE),
                    doneAt = l(NotesAspectSeeder.FIELD_DONE_AT),
                    sortOrder = i(NotesAspectSeeder.FIELD_SORT_ORDER),
                    triggerPlaceLabel = s(NotesAspectSeeder.FIELD_TRIGGER_PLACE_LABEL),
                    repeatKind = s(NotesAspectSeeder.FIELD_REPEAT_KIND),
                    repeatEvery = i(NotesAspectSeeder.FIELD_REPEAT_EVERY),
                    repeatDaysOfWeek = s(NotesAspectSeeder.FIELD_REPEAT_DAYS_OF_WEEK),
                    repeatDay = i(NotesAspectSeeder.FIELD_REPEAT_DAY),
                    repeatMonth = i(NotesAspectSeeder.FIELD_REPEAT_MONTH),
                    repeatEndKind = s(NotesAspectSeeder.FIELD_REPEAT_END_KIND),
                    repeatEndDate = l(NotesAspectSeeder.FIELD_REPEAT_END_DATE),
                    repeatEndCount = i(NotesAspectSeeder.FIELD_REPEAT_END_COUNT),
                    exact = b(NotesAspectSeeder.FIELD_EXACT),
                    exactDowngraded = b(NotesAspectSeeder.FIELD_EXACT_DOWNGRADED),
                    missedAt = l(NotesAspectSeeder.FIELD_MISSED_AT),
                    missedDismissedAt = l(NotesAspectSeeder.FIELD_MISSED_DISMISSED_AT),
                    loggedAt = l(NotesAspectSeeder.FIELD_LOGGED_AT),
                    updatedAtMs = record.updatedAt,
                    createdAt = record.createdAt,
                    deleted = false,
                ),
            )
            itemsCopied++
        }

        // Dates `Event` records - see this object's own class doc for why nothing on the
        // unconfigured path reads these back yet, and why they are copied anyway.
        var eventsCopied = 0
        for (record in db.engineRecordDao().activeByRecordType(datesSchema.recordTypeId)) {
            if (db.eventDao().getById(record.id) != null) continue

            val payload = JSONObject(record.payload)
            fun s(name: String) = PayloadCodec.readString(payload, datesSchema.fieldIds.getValue(name))
            fun l(name: String) = PayloadCodec.readLong(payload, datesSchema.fieldIds.getValue(name))
            fun b(name: String) = PayloadCodec.readBoolean(payload, datesSchema.fieldIds.getValue(name))

            val title = s(DatesAspectSeeder.FIELD_TITLE) ?: continue
            val start = l(DatesAspectSeeder.FIELD_START) ?: continue

            db.eventDao().insert(
                Event(
                    id = record.id,
                    serverId = java.util.UUID.randomUUID().toString(),
                    title = title,
                    startsAt = start,
                    endsAt = l(DatesAspectSeeder.FIELD_END),
                    allDay = b(DatesAspectSeeder.FIELD_ALL_DAY),
                    location = s(DatesAspectSeeder.FIELD_LOCATION),
                    notes = s(DatesAspectSeeder.FIELD_NOTES),
                    // Added 2026-08-28 alongside MIGRATION_47_48 - the column did not exist when
                    // this copier was first written, so the engine's own structuredMeta field was
                    // silently left behind on every historical Dates event this copy touches. See
                    // that migration's own doc comment for why an unread Room column stopped being
                    // an acceptable place to leave this value.
                    structuredMeta = s(DatesAspectSeeder.FIELD_STRUCTURED_META),
                    source = s(DatesAspectSeeder.FIELD_SOURCE) ?: DatesAspectSeeder.SOURCE_LEGION,
                    googleEventId = s(DatesAspectSeeder.FIELD_GOOGLE_EVENT_ID),
                    kind = EventKind.EVENT,
                    updatedAtMs = record.updatedAt,
                    createdAt = record.createdAt,
                    deleted = false,
                ),
            )
            eventsCopied++
        }

        prefs.edit().putBoolean(KEY_COMPLETED, true).apply()
        return Result(itemsCopied = itemsCopied, eventsCopied = eventsCopied, alreadyDone = false)
    }
}
