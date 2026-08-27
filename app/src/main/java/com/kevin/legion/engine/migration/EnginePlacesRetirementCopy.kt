package com.kevin.legion.engine.migration

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.TaggedPlace
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.places.PlacesAspectSeeder
import org.json.JSONObject

/**
 * Step 1 of the engine retirement sequence (`.scratch/backend-erp/issues/15-engine-retirement-sequence.md`):
 * the one-time, idempotent copier that reconciles the engine's Place records onto the legacy
 * `places` table BEFORE `PlaceController`'s unconfigured read/write path is repointed off the
 * engine and onto [com.kevin.legion.data.local.PlaceDao].
 *
 * **Runs the OPPOSITE direction from [EngineDataMigrationWave1.copyPlacesIfNeeded].** That copier
 * carried `places` INTO the engine on 2026-08-23, when the engine was new and `places` was the
 * only truth there was. Ticket 15 repoints the unconfigured path back onto `places`, so any Place
 * tagged directly through the engine since wave 1 - every voice tag made on an unconfigured
 * install between 2026-08-23 and this repoint - exists ONLY in the engine and has to land in
 * `places` first, or flipping the read would silently lose it.
 *
 * **Identity is by label, not by [com.kevin.legion.data.local.EngineRecord.guid].**
 * [TaggedPlace.label] is `places`'s `@PrimaryKey` and the natural key `PlaceController` has always
 * upserted on. Wave 1's forward copy minted a deterministic guid from the label
 * (`UUID.nameUUIDFromBytes`), but every place tagged directly through the engine's unconfigured
 * `PlaceController.tagPlace` since then got a random one (`RecordStore.create`'s default) - so
 * guid cannot be used to match a `places` row against its engine counterpart in this direction.
 *
 * **Reconcile-and-repoint, never blind-switch (ticket 05's rule): this only ever fills gaps.** A
 * label already present in `places` - active OR tombstoned - is left alone. `places` has been the
 * live write target of the CONFIGURED path since cutover 1, so an existing row there is presumed
 * at least as current as the engine's; and a tombstoned label is a place the user deliberately
 * forgot, which copying the engine's (necessarily stale, since nothing writes the engine path once
 * configured) record back in would silently resurrect.
 *
 * **Deletes nothing.** Ticket 15 is explicit: nothing is deleted until every aspect is repointed
 * and soaked, so the engine's Place records are read here and never trashed, updated, or touched.
 */
object EnginePlacesRetirementCopy {
    private const val PREFS = "engine_places_retirement"
    private const val KEY_COMPLETED = "places_repointed_v1"

    /** [copied] counts only rows actually written this call. [alreadyDone] is true only when the
     * SharedPreferences fast path skipped the pass entirely without even reading the engine. */
    data class Result(val copied: Int, val alreadyDone: Boolean)

    /**
     * Copies every active engine Place record whose label has no row at all in `places` into
     * `places`. Idempotent two ways, matching [EngineDataMigrationWave1]'s own shape: the
     * [KEY_COMPLETED] flag short-circuits every call after the first successful pass, and even
     * without it a re-run is safe because the per-label existence check simply finds nothing left
     * to copy the second time.
     */
    suspend fun copyIfNeeded(context: Context): Result {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_COMPLETED, false)) return Result(copied = 0, alreadyDone = true)

        val db = CarDatabase.getDatabase(context)
        val schema = PlacesAspectSeeder.ensureSeeded(context)
        val engineRecords = db.engineRecordDao().activeByRecordType(schema.recordTypeId)

        // Every label `places` has ever seen, active or tombstoned - see this object's class doc
        // for why a tombstoned label must block a copy too, not just an active one.
        val existingLabels = db.placeDao().getAllLabels().toHashSet()

        var copied = 0
        for (record in engineRecords) {
            val payload = JSONObject(record.payload)
            val label = PayloadCodec.readString(payload, schema.fieldIds.getValue(PlacesAspectSeeder.FIELD_LABEL))
                ?: continue
            if (label in existingLabels) continue // `places` wins ties - reconcile, never overwrite

            val lat = PayloadCodec.readDouble(payload, schema.fieldIds.getValue(PlacesAspectSeeder.FIELD_LATITUDE)) ?: continue
            val lon = PayloadCodec.readDouble(payload, schema.fieldIds.getValue(PlacesAspectSeeder.FIELD_LONGITUDE)) ?: continue

            db.placeDao().upsert(
                TaggedPlace(
                    label = label,
                    latitude = lat,
                    longitude = lon,
                    timestamp = record.updatedAt,
                    deleted = false,
                )
            )
            existingLabels += label // guards two engine records that somehow share a label within one pass
            copied++
        }

        prefs.edit().putBoolean(KEY_COMPLETED, true).apply()
        return Result(copied = copied, alreadyDone = false)
    }
}
