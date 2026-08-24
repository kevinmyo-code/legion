package com.kevin.legion.engine.places

import android.content.Context
import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordType

/**
 * The built-in Places aspect - Wave 1 of `.scratch/aspect-engine/issues/21-migration-waves.md`.
 * Mirrors [com.kevin.legion.engine.notes.NotesAspectSeeder]/
 * [com.kevin.legion.engine.dates.DatesAspectSeeder]'s exact shape - idempotent at every
 * granularity, `ownerPluginId`/`locked` per ticket 11 answer point 1.
 *
 * **The carve, in full, is `docs/architecture/wave1-carve-2026-08-23.md`.** One `Place` record
 * type mirroring [com.kevin.legion.data.local.TaggedPlace] - label plus coordinates, all three
 * required and locked (a place with neither is not a place). No due-date field: a saved place
 * carries no clock, so [RecordType.primaryDueDateFieldId] is left null, same as any record type
 * with nothing date-shaped.
 */
object PlacesAspectSeeder {
    const val ASPECT_NAME = "Places"
    const val RECORD_TYPE_NAME = "Place"
    const val OWNER_PLUGIN_ID = "places"

    const val FIELD_LABEL = "label"
    const val FIELD_LATITUDE = "latitude"
    const val FIELD_LONGITUDE = "longitude"

    data class Schema(
        val aspectId: Long,
        val recordTypeId: Long,
        val fieldIds: Map<String, Long>,
    )

    suspend fun ensureSeeded(context: Context): Schema {
        val db = CarDatabase.getDatabase(context)
        val now = System.currentTimeMillis()

        val aspectId = db.aspectDao().listActive().find { it.name == ASPECT_NAME }?.id
            ?: db.aspectDao().insert(
                Aspect(name = ASPECT_NAME, icon = "place", color = "", position = 2, createdAt = now, updatedAt = now),
            )

        val recordTypeId = db.recordTypeDao().listByAspect(aspectId).find { it.name == RECORD_TYPE_NAME }?.id
            ?: db.recordTypeDao().insert(
                RecordType(aspectId = aspectId, name = RECORD_TYPE_NAME, createdAt = now, updatedAt = now),
            )

        val existingFields = db.fieldDefDao().forRecordType(recordTypeId).associateBy { it.name }

        suspend fun ensureField(name: String, type: FieldType, position: Int): Long {
            existingFields[name]?.let { return it.id }
            return db.fieldDefDao().insert(
                FieldDef(
                    recordTypeId = recordTypeId,
                    name = name,
                    type = type,
                    required = true,
                    position = position,
                    ownerPluginId = OWNER_PLUGIN_ID,
                    locked = true,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }

        val fieldIds = mutableMapOf<String, Long>()
        fieldIds[FIELD_LABEL] = ensureField(FIELD_LABEL, FieldType.TEXT, position = 0)
        fieldIds[FIELD_LATITUDE] = ensureField(FIELD_LATITUDE, FieldType.NUMBER, position = 1)
        fieldIds[FIELD_LONGITUDE] = ensureField(FIELD_LONGITUDE, FieldType.NUMBER, position = 2)

        return Schema(aspectId, recordTypeId, fieldIds)
    }
}
