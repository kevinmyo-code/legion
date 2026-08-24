package com.kevin.legion.engine.migration

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.notes.NotesAspectSeeder
import com.kevin.legion.engine.places.PlacesAspectSeeder
import java.util.UUID

/**
 * The one-time, idempotent copier that carries Wave 1's live data
 * (`.scratch/aspect-engine/issues/21-migration-waves.md` point 2) - every
 * [com.kevin.legion.data.local.ListItem] and every [com.kevin.legion.data.local.TaggedPlace] -
 * onto the engine through [RecordStore], the engine's single write door. **Additive only**: reads
 * the legacy tables, writes new [com.kevin.legion.data.local.EngineRecord] rows; never touches,
 * drops, or mutates a legacy table. The old tables and every old screen/tool keep working
 * unchanged - cutover is a later, per-aspect wave (ticket 14 point 2), not this one.
 *
 * The exact field mapping and what is deliberately NOT carried (`ItemList`'s grouping,
 * `ListItem.loggedAt`, tombstoned rows of either source) is `docs/architecture/wave1-carve-2026-08-23.md`.
 *
 * **Two independent layers of idempotency, matching [com.kevin.legion.data.MidnightImport]'s own
 * "GUARDED TO RUN AT MOST ONCE" shape** (that object's doc comment is the worked precedent this
 * one follows):
 *
 * 1. **A SharedPreferences completion flag per domain** ([KEY_NOTES_COMPLETED]/
 *    [KEY_PLACES_COMPLETED]) - the fast path. Once a domain's full pass completes with no
 *    exception, its flag is set and every later app start skips that domain's query and loop
 *    entirely.
 * 2. **A per-row identity check against [com.kevin.legion.data.local.EngineRecordDao.getByGuid]** -
 *    the correctness backstop for the case the flag-only design in [com.kevin.legion.data.MidnightImport]
 *    itself accepts as a known limitation: a crash partway through the loop leaves the flag unset,
 *    so the NEXT app start retries the whole domain from scratch. Without a stable, deterministic
 *    `guid` per source row, that retry would re-create every row the first, interrupted pass had
 *    already written. [copyNotesIfNeeded] reuses [com.kevin.legion.data.local.ListItem.syncId]
 *    directly (already a stable per-row UUID minted for exactly this cross-device-identity
 *    purpose); [copyPlacesIfNeeded] derives a deterministic UUID from [com.kevin.legion.data.local.TaggedPlace.label]
 *    (`UUID.nameUUIDFromBytes`) since that table carries no such column of its own. Either way, a
 *    retry recognizes and skips a row it already created instead of duplicating it.
 */
object EngineDataMigrationWave1 {
    private const val PREFS = "engine_migration_wave1"
    private const val KEY_NOTES_COMPLETED = "notes_completed_v1"
    private const val KEY_PLACES_COMPLETED = "places_completed_v1"

    /** [copied] counts only rows actually written this call - a row skipped because its `guid`
     * already existed (the per-row idempotency backstop) is not counted twice across retries.
     * [alreadyDone] is true only when the SharedPreferences fast path skipped the whole domain
     * without even querying the legacy table. */
    data class Result(val copied: Int, val alreadyDone: Boolean)

    private fun store(db: CarDatabase) = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())

    /**
     * Copies every non-tombstoned [com.kevin.legion.data.local.ListItem] (across every legacy
     * [com.kevin.legion.data.local.ItemList] - `ListItemDao.allActive()` already reads across all
     * of them, matching the live app's own "one list" reality per the carve doc) into the Notes
     * aspect's `Item` record type. Provenance is always [RecordProvenance.USER] - every source row
     * here was voice- or hand-entered, never document-extracted, so CLAUDE.md §4's reconciliation
     * gate does not apply (this is a storage-location change for data the user already owns, not
     * an ingestion path).
     */
    suspend fun copyNotesIfNeeded(context: Context): Result {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTES_COMPLETED, false)) return Result(copied = 0, alreadyDone = true)

        val db = CarDatabase.getDatabase(context)
        val schema = NotesAspectSeeder.ensureSeeded(context)
        val recordStore = store(db)
        val items = db.listItemDao().allActive()

        var copied = 0
        for (item in items) {
            val guid = item.syncId
            if (db.engineRecordDao().getByGuid(guid) != null) continue // already copied by an earlier, interrupted pass

            val fieldValues: Map<Long, Any?> = mapOf(
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_TEXT) to item.text,
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_DONE) to item.done,
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_DONE_AT) to item.doneAt,
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_SORT_ORDER) to item.sortOrder,
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_STARTS_AT) to item.startsAt,
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_ENDS_AT) to item.endsAt,
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_ALL_DAY) to item.allDay,
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_TRIGGER_PLACE_LABEL) to item.triggerPlaceLabel,
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_REPEAT_KIND) to item.repeatKind,
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_REPEAT_EVERY) to item.repeatEvery,
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_REPEAT_DAYS_OF_WEEK) to item.repeatDaysOfWeek,
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_REPEAT_DAY) to item.repeatDay,
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_REPEAT_MONTH) to item.repeatMonth,
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_REPEAT_END_KIND) to item.repeatEndKind,
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_REPEAT_END_DATE) to item.repeatEndDate,
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_REPEAT_END_COUNT) to item.repeatEndCount,
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_EXACT) to item.exact,
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_EXACT_DOWNGRADED) to item.exactDowngraded,
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_MISSED_AT) to item.missedAt,
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_MISSED_DISMISSED_AT) to item.missedDismissedAt,
            )

            // Both clocks land in ONE atomic write - RecordStore.create's `updatedAt` parameter
            // (senior review, 2026-08-23) exists for exactly this: a source row whose createdAt
            // and updatedAt differ is preserved exactly, in a single call, with no window between
            // two writes for a crash to land in. See this object's own class doc for the shape
            // that replaced (create, then a conditional update()) and why it was unsafe.
            val result = recordStore.create(
                recordTypeId = schema.recordTypeId,
                fieldValues = fieldValues,
                provenance = RecordProvenance.USER,
                now = item.createdAt,
                guid = guid,
                updatedAt = item.updatedAt,
            )
            if (result is RecordStore.WriteResult.Success) copied++
        }

        prefs.edit().putBoolean(KEY_NOTES_COMPLETED, true).apply()
        return Result(copied = copied, alreadyDone = false)
    }

    /**
     * Copies every non-tombstoned [com.kevin.legion.data.local.TaggedPlace] into the Places
     * aspect's `Place` record type. Same provenance/timestamp/idempotency reasoning as
     * [copyNotesIfNeeded] - see that function's and this object's own doc comments.
     */
    suspend fun copyPlacesIfNeeded(context: Context): Result {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PLACES_COMPLETED, false)) return Result(copied = 0, alreadyDone = true)

        val db = CarDatabase.getDatabase(context)
        val schema = PlacesAspectSeeder.ensureSeeded(context)
        val recordStore = store(db)
        val places = db.placeDao().getAll()

        var copied = 0
        for (place in places) {
            // Deterministic, not random - TaggedPlace carries no syncId column of its own, and a
            // stable guid derived from its own natural key (label) is what lets a retry after a
            // mid-loop crash recognize a row it already wrote instead of duplicating it.
            val guid = UUID.nameUUIDFromBytes(place.label.toByteArray()).toString()
            if (db.engineRecordDao().getByGuid(guid) != null) continue

            val fieldValues: Map<Long, Any?> = mapOf(
                schema.fieldIds.getValue(PlacesAspectSeeder.FIELD_LABEL) to place.label,
                schema.fieldIds.getValue(PlacesAspectSeeder.FIELD_LATITUDE) to place.latitude,
                schema.fieldIds.getValue(PlacesAspectSeeder.FIELD_LONGITUDE) to place.longitude,
            )

            // TaggedPlace has one clock column only (timestamp) - `create`'s `updatedAt` defaults
            // to `now`, so leaving it unset here already reuses the same value for both.
            val result = recordStore.create(
                recordTypeId = schema.recordTypeId,
                fieldValues = fieldValues,
                provenance = RecordProvenance.USER,
                now = place.timestamp,
                guid = guid,
            )
            if (result is RecordStore.WriteResult.Success) copied++
        }

        prefs.edit().putBoolean(KEY_PLACES_COMPLETED, true).apply()
        return Result(copied = copied, alreadyDone = false)
    }

    /** App-start convenience - both domains, each wrapped so one's failure can never cost the
     * other, same L12 "independent failure mode" reasoning [com.kevin.legion.MidnightApplication]'s
     * own app-start block already uses for its neighbouring one-time-seed calls. Callers that want
     * the per-domain [Result] (tests, a future settings-screen "migrate now" button) should call
     * [copyNotesIfNeeded]/[copyPlacesIfNeeded] directly instead. */
    suspend fun runAll(context: Context) {
        runCatching { copyNotesIfNeeded(context) }
        runCatching { copyPlacesIfNeeded(context) }
    }
}
