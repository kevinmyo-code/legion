package com.kevin.legion.engine.notes

import android.content.Context
import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordType
import com.kevin.legion.engine.FieldConfig

/**
 * The built-in Notes aspect - Wave 1 of `.scratch/aspect-engine/issues/21-migration-waves.md`
 * ("notes/lists/places, then pantry, then ledger, then fleet"). Follows
 * [com.kevin.legion.engine.dates.DatesAspectSeeder]'s exact shape (idempotent at every
 * granularity, `ownerPluginId`/`locked` per ticket 11 answer point 1) - see that object's own doc
 * comment for the reasoning this one does not repeat.
 *
 * **The carve, in full, is `docs/architecture/wave1-carve-2026-08-23.md`.** Its headline finding:
 * notes and lists are not two domains in the live app - a prior effort
 * (`.scratch/notes-lists-calendar/`) already folded both, plus `CarTask` and `PlaceReminder`,
 * into one flat [com.kevin.legion.data.local.ListItem] model with exactly one live
 * [com.kevin.legion.data.local.ItemList]. This seeder's `Item` record type is that unified shape -
 * checklist entry, note line, and reminder (time- or place-triggered) are all the same record,
 * distinguished only by which optional fields carry a value, matching
 * [com.kevin.legion.data.local.ListItem]'s own doc comment exactly.
 *
 * **Not carried from `ListItem`:** `listId` (the one live list makes list-grouping vestigial - see
 * the carve doc) and `loggedAt` (workout-sweep bookkeeping owned by
 * [com.kevin.legion.advisor.GoalChecklistSync], not user content).
 */
object NotesAspectSeeder {
    const val ASPECT_NAME = "Notes"
    const val RECORD_TYPE_NAME = "Item"
    const val OWNER_PLUGIN_ID = "notes"

    const val FIELD_TEXT = "text"
    const val FIELD_DONE = "done"
    const val FIELD_DONE_AT = "doneAt"
    const val FIELD_SORT_ORDER = "sortOrder"
    const val FIELD_STARTS_AT = "startsAt"
    const val FIELD_ENDS_AT = "endsAt"
    const val FIELD_ALL_DAY = "allDay"
    const val FIELD_TRIGGER_PLACE_LABEL = "triggerPlaceLabel"
    const val FIELD_REPEAT_KIND = "repeatKind"
    const val FIELD_REPEAT_EVERY = "repeatEvery"
    const val FIELD_REPEAT_DAYS_OF_WEEK = "repeatDaysOfWeek"
    const val FIELD_REPEAT_DAY = "repeatDay"
    const val FIELD_REPEAT_MONTH = "repeatMonth"
    const val FIELD_REPEAT_END_KIND = "repeatEndKind"
    const val FIELD_REPEAT_END_DATE = "repeatEndDate"
    const val FIELD_REPEAT_END_COUNT = "repeatEndCount"
    const val FIELD_EXACT = "exact"
    const val FIELD_EXACT_DOWNGRADED = "exactDowngraded"
    const val FIELD_MISSED_AT = "missedAt"
    const val FIELD_MISSED_DISMISSED_AT = "missedDismissedAt"
    /** Cutover 1 addition (`docs/architecture/cutover1-2026-08-24.md`) - the wave 1 carve
     * deliberately did not carry [com.kevin.legion.data.local.ListItem.loggedAt] onto the engine
     * (it is `GoalChecklistSync`'s own sweep bookkeeping, not user content), reasoning that was
     * only true as long as the legacy table stayed the live store. Once `NotesController` stops
     * writing `list_items` at all, that bookkeeping needs somewhere to live - a NEW field on the
     * SAME record type, added the way the engine is designed to grow (a `FieldDef` row, not a Room
     * migration) rather than a schema exception. [NotesAspectSeeder.ensureSeeded] is idempotent at
     * per-field granularity, so this lands for every existing install on its next seed pass with no
     * migration of its own. */
    const val FIELD_LOGGED_AT = "loggedAt"

    /** [com.kevin.legion.notes.RepeatKind]'s names, duplicated here as plain strings rather than a
     * dependency on that enum - `engine/` stays independent of `notes/`, matching the engine's own
     * "engine owns record types... never user-authorable" layering (charter decision 2) applied to
     * package direction too: a plugin's domain package may depend on the engine, never the reverse. */
    val REPEAT_KIND_OPTIONS = listOf("DAILY", "WEEKLY", "MONTHLY_ON_DATE", "YEARLY")

    /** [com.kevin.legion.notes.RepeatEndKind]'s names - see [REPEAT_KIND_OPTIONS]'s doc comment. */
    val REPEAT_END_KIND_OPTIONS = listOf("NEVER", "ON_DATE", "AFTER_COUNT")

    /** See [com.kevin.legion.engine.dates.DatesAspectSeeder.Schema] - identical shape, identical
     * reasoning (field ids are `AUTOINCREMENT`, not known at compile time). */
    data class Schema(
        val aspectId: Long,
        val recordTypeId: Long,
        val fieldIds: Map<String, Long>,
    )

    /** Idempotent at every granularity - see
     * [com.kevin.legion.engine.dates.DatesAspectSeeder.ensureSeeded]'s doc comment for the exact
     * mechanism (matched by name at each level, not a single top-level flag). */
    suspend fun ensureSeeded(context: Context): Schema {
        val db = CarDatabase.getDatabase(context)
        val now = System.currentTimeMillis()

        val aspectId = db.aspectDao().listActive().find { it.name == ASPECT_NAME }?.id
            ?: db.aspectDao().insert(
                Aspect(name = ASPECT_NAME, icon = "notes", color = "", position = 1, createdAt = now, updatedAt = now),
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
        fieldIds[FIELD_TEXT] = ensureField(FIELD_TEXT, FieldType.TEXT, required = true, position = 0)
        fieldIds[FIELD_DONE] = ensureField(FIELD_DONE, FieldType.BOOLEAN, required = true, position = 1)
        fieldIds[FIELD_DONE_AT] = ensureField(FIELD_DONE_AT, FieldType.DATETIME, required = false, position = 2)
        fieldIds[FIELD_SORT_ORDER] = ensureField(FIELD_SORT_ORDER, FieldType.NUMBER, required = false, position = 3)
        fieldIds[FIELD_STARTS_AT] = ensureField(FIELD_STARTS_AT, FieldType.DATETIME, required = false, position = 4)
        fieldIds[FIELD_ENDS_AT] = ensureField(FIELD_ENDS_AT, FieldType.DATETIME, required = false, position = 5)
        fieldIds[FIELD_ALL_DAY] = ensureField(FIELD_ALL_DAY, FieldType.BOOLEAN, required = false, position = 6)
        fieldIds[FIELD_TRIGGER_PLACE_LABEL] = ensureField(FIELD_TRIGGER_PLACE_LABEL, FieldType.TEXT, required = false, position = 7)
        fieldIds[FIELD_REPEAT_KIND] = ensureField(
            FIELD_REPEAT_KIND, FieldType.CHOICE, required = false, position = 8,
            config = FieldConfig.serializeChoice(REPEAT_KIND_OPTIONS),
        )
        fieldIds[FIELD_REPEAT_EVERY] = ensureField(FIELD_REPEAT_EVERY, FieldType.NUMBER, required = false, position = 9)
        fieldIds[FIELD_REPEAT_DAYS_OF_WEEK] = ensureField(FIELD_REPEAT_DAYS_OF_WEEK, FieldType.TEXT, required = false, position = 10)
        fieldIds[FIELD_REPEAT_DAY] = ensureField(FIELD_REPEAT_DAY, FieldType.NUMBER, required = false, position = 11)
        fieldIds[FIELD_REPEAT_MONTH] = ensureField(FIELD_REPEAT_MONTH, FieldType.NUMBER, required = false, position = 12)
        fieldIds[FIELD_REPEAT_END_KIND] = ensureField(
            FIELD_REPEAT_END_KIND, FieldType.CHOICE, required = false, position = 13,
            config = FieldConfig.serializeChoice(REPEAT_END_KIND_OPTIONS),
        )
        fieldIds[FIELD_REPEAT_END_DATE] = ensureField(FIELD_REPEAT_END_DATE, FieldType.DATE, required = false, position = 14)
        fieldIds[FIELD_REPEAT_END_COUNT] = ensureField(FIELD_REPEAT_END_COUNT, FieldType.NUMBER, required = false, position = 15)
        fieldIds[FIELD_EXACT] = ensureField(FIELD_EXACT, FieldType.BOOLEAN, required = false, position = 16)
        fieldIds[FIELD_EXACT_DOWNGRADED] = ensureField(FIELD_EXACT_DOWNGRADED, FieldType.BOOLEAN, required = false, position = 17)
        fieldIds[FIELD_MISSED_AT] = ensureField(FIELD_MISSED_AT, FieldType.DATETIME, required = false, position = 18)
        fieldIds[FIELD_MISSED_DISMISSED_AT] = ensureField(FIELD_MISSED_DISMISSED_AT, FieldType.DATETIME, required = false, position = 19)
        fieldIds[FIELD_LOGGED_AT] = ensureField(FIELD_LOGGED_AT, FieldType.DATETIME, required = false, position = 20)

        // startsAt is the reminder clock - wiring it as the promoted dueAt is what makes a Notes
        // item with a time trigger show up in a cross-aspect agenda, same mechanism
        // DatesAspectSeeder uses for its own FIELD_START.
        val recordType = db.recordTypeDao().getById(recordTypeId)!!
        if (recordType.primaryDueDateFieldId != fieldIds[FIELD_STARTS_AT]) {
            db.recordTypeDao().update(recordType.copy(primaryDueDateFieldId = fieldIds[FIELD_STARTS_AT], updatedAt = now))
        }

        return Schema(aspectId, recordTypeId, fieldIds)
    }
}
