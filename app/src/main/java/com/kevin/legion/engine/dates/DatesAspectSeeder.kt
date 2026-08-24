package com.kevin.legion.engine.dates

import android.content.Context
import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordType
import com.kevin.legion.engine.FieldConfig

/**
 * The built-in Dates aspect (`.scratch/aspect-engine/issues/19-build-dates-aspect.md` point 1,
 * locked at `.scratch/aspect-engine/issues/05-central-date-database.md` answer point 1: "a
 * built-in Dates aspect on the engine itself... events are records; voice CRUD, widgets, generated
 * screens, and the mirror come free"). This object is the SEEDER, run once (idempotently, see
 * [ensureSeeded]) rather than a fixed schema baked into a Room migration - the whole point of the
 * engine (ticket 16) is that a new aspect is rows in [Aspect]/[RecordType]/[FieldDef], never a new
 * `@Entity`, and Dates is the engine's own proof of that (ticket 05 answer point 1's "proves the
 * engine can carry a core feature").
 *
 * **Plugin binding (ticket 11 answer point 1, "partially editable" - required fields locked,
 * everything else user-ownable).** There is no separate capability-plugin registry yet (ticket 11's
 * "a plugin registers... through the single RecordStore door" is itself still just a design, not a
 * built mechanism - ticket 17/18 are the next build tickets on that map, not this one). What DOES
 * already exist on the engine core, built at ticket 16, is exactly the two [FieldDef] columns that
 * contract needs: [FieldDef.ownerPluginId] and [FieldDef.locked]. This seeder uses those directly -
 * every field it creates carries [OWNER_PLUGIN_ID], and a field marked [FieldDef.required] is also
 * [FieldDef.locked] (the user cannot delete `title`/`start`/`source`, but CAN delete `end`/
 * `location`/`notes`/`googleEventId`, add fields, reorder, relabel). That is the whole "minimal
 * RequiredFields declaration checked by the schema-edit path" this ticket's brief allowed for in
 * place of a fuller plugin API that has not been built yet - stated here rather than assumed,
 * because ticket 11's fuller mechanism (a plugin registering VERBS/WIDGETS/WORKERS, not just field
 * locks) is real future work this seeder does not attempt.
 *
 * **[googleEventId] is marked optional per the ticket's own field list**, not required/locked, even
 * though deleting it would silently break [com.kevin.legion.calendar.CalendarImportController]'s
 * dedup key for every already-imported Google event (a fresh import would no longer find its match
 * and would re-create every one). This is a known, accepted v1 gap - the field-locking granularity
 * this seeder has access to is "required or not", and `googleEventId` is genuinely optional from a
 * USER's point of view (a legion-created event never has one) even though it is load-bearing for
 * the PLUGIN. A future real capability-plugin API (ticket 11's fuller shape) would let a plugin
 * lock a field it needs internally without marking it required-for-the-user; this seeder cannot
 * express that distinction yet.
 */
object DatesAspectSeeder {
    const val ASPECT_NAME = "Dates"
    const val RECORD_TYPE_NAME = "Event"
    const val OWNER_PLUGIN_ID = "dates"

    const val FIELD_TITLE = "title"
    const val FIELD_START = "start"
    const val FIELD_END = "end"
    const val FIELD_LOCATION = "location"
    const val FIELD_NOTES = "notes"
    const val FIELD_SOURCE = "source"
    const val FIELD_GOOGLE_EVENT_ID = "googleEventId"

    const val SOURCE_LEGION = "legion"
    const val SOURCE_GOOGLE = "google"

    /** The seeded shape's ids, resolved fresh (Room reads, all cheap) by every caller that needs
     * them - [com.kevin.legion.calendar.CalendarImportController],
     * [com.kevin.legion.engine.dates.DatesAgenda] and any future voice/UI write path. Field ids are
     * assigned by SQLite `AUTOINCREMENT` at seed time, not known at compile time, which is exactly
     * why this exists rather than a set of `const val` field ids. */
    data class Schema(
        val aspectId: Long,
        val recordTypeId: Long,
        val fieldIds: Map<String, Long>,
    )

    /**
     * Creates whatever part of the Dates aspect (aspect row, record type row, each field def, the
     * `primaryDueDateFieldId` wiring) does not already exist, and returns the resolved [Schema]
     * either way. **Idempotent at every granularity, not just at the top level** - matched by name
     * at each of the four levels (aspect name, record type name, field name, the
     * `primaryDueDateFieldId` value itself) rather than a single "have I ever run" flag, so a
     * process death partway through a first run still converges to the complete shape the next
     * time this is called, instead of leaving a half-seeded aspect forever because a top-level
     * "already seeded" flag was set too early.
     */
    suspend fun ensureSeeded(context: Context): Schema {
        val db = CarDatabase.getDatabase(context)
        val now = System.currentTimeMillis()

        val aspectId = db.aspectDao().listActive().find { it.name == ASPECT_NAME }?.id
            ?: db.aspectDao().insert(
                Aspect(name = ASPECT_NAME, icon = "calendar", color = "", position = 0, createdAt = now, updatedAt = now),
            )

        val recordTypeId = db.recordTypeDao().listByAspect(aspectId).find { it.name == RECORD_TYPE_NAME }?.id
            ?: db.recordTypeDao().insert(
                RecordType(aspectId = aspectId, name = RECORD_TYPE_NAME, createdAt = now, updatedAt = now),
            )

        val existingFields = db.fieldDefDao().forRecordType(recordTypeId).associateBy { it.name }

        suspend fun ensureField(name: String, type: FieldType, required: Boolean, position: Int, config: String? = null): Long {
            existingFields[name]?.let { return it.id }
            return db.fieldDefDao().insert(
                FieldDef(
                    recordTypeId = recordTypeId,
                    name = name,
                    type = type,
                    required = required,
                    position = position,
                    config = config,
                    ownerPluginId = OWNER_PLUGIN_ID,
                    locked = required,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }

        val fieldIds = mutableMapOf<String, Long>()
        fieldIds[FIELD_TITLE] = ensureField(FIELD_TITLE, FieldType.TEXT, required = true, position = 0)
        fieldIds[FIELD_START] = ensureField(FIELD_START, FieldType.DATETIME, required = true, position = 1)
        fieldIds[FIELD_END] = ensureField(FIELD_END, FieldType.DATETIME, required = false, position = 2)
        fieldIds[FIELD_LOCATION] = ensureField(FIELD_LOCATION, FieldType.TEXT, required = false, position = 3)
        fieldIds[FIELD_NOTES] = ensureField(FIELD_NOTES, FieldType.TEXT, required = false, position = 4)
        fieldIds[FIELD_SOURCE] = ensureField(
            FIELD_SOURCE, FieldType.CHOICE, required = true, position = 5,
            config = FieldConfig.serializeChoice(listOf(SOURCE_LEGION, SOURCE_GOOGLE)),
        )
        fieldIds[FIELD_GOOGLE_EVENT_ID] = ensureField(FIELD_GOOGLE_EVENT_ID, FieldType.TEXT, required = false, position = 6)

        // The promoted-dueAt wiring (ticket 03 answer point 1) - RecordStore reads
        // RecordType.primaryDueDateFieldId to know which field mirrors into EngineRecord.dueAt,
        // and that is what makes a Dates event show up in DatesAgenda's cross-aspect query at all.
        val recordType = db.recordTypeDao().getById(recordTypeId)!!
        if (recordType.primaryDueDateFieldId != fieldIds[FIELD_START]) {
            db.recordTypeDao().update(recordType.copy(primaryDueDateFieldId = fieldIds[FIELD_START], updatedAt = now))
        }

        return Schema(aspectId, recordTypeId, fieldIds)
    }
}
