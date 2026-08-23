package com.kevin.legion.engine

import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType

/**
 * The generated add/edit form's pure validation layer (aspect-engine ticket 18, ticket 10 answer
 * point 3: "forms enforce required fields and speak validation and quarantine in words"). No Compose
 * import, no Room/DAO access, no [RecordStore] call - a plain function of already-collected form
 * state, so it is unit-testable without Robolectric, matching this repo's "pure parts stay pure"
 * split ([com.kevin.legion.ui.common.DeckChartData]'s own file doc names the same posture).
 *
 * This is deliberately NARROWER than [RecordStore]'s own write-time checks: it never touches the
 * database (a reference target's existence, in particular, is [RecordStore.validateReferences]'s job
 * alone, since only a real DAO read can answer it) - this layer catches what the FORM itself can
 * already tell is wrong before a submit is even attempted: a required field left empty, a value of
 * the wrong Kotlin shape for its [FieldType]. A form that passes this check can still come back from
 * `RecordStore.create`/`update` as a [RecordStore.WriteResult.Failure] (a stale reference, a deleted
 * field), and the generated form screen surfaces THAT failure the same way, in words - this class is
 * the fast, offline half of the same "quarantine messaging in words" rule, not the whole of it.
 */
object GeneratedFormValidation {
    data class FieldError(val fieldId: Long, val fieldName: String, val message: String)

    /**
     * Every problem [values] has against [fieldDefs], in field order - empty when the form is clean
     * enough to submit. [FieldType.COMPUTED] fields are skipped entirely (never user-supplied, same
     * "computed field entries in fieldValues are IGNORED" rule [RecordStore.create]'s own doc states).
     */
    fun validate(fieldDefs: List<FieldDef>, values: Map<Long, Any?>): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        for (fd in fieldDefs) {
            if (fd.type == FieldType.COMPUTED) continue
            val hasKey = values.containsKey(fd.id)
            val raw = values[fd.id]
            val blank = raw == null || (raw is String && raw.isBlank())
            if (fd.required && (!hasKey || blank)) {
                errors += FieldError(fd.id, fd.name, "'${fd.name}' is required")
                continue
            }
            if (!hasKey || raw == null) continue
            if (!shapeMatches(fd.type, raw)) {
                errors += FieldError(fd.id, fd.name, "'${fd.name}' has an unexpected value type")
            }
        }
        return errors
    }

    /** True when this build value's runtime Kotlin type matches what [PayloadCodec.write] expects
     * for [type] - mirrors that function's own `when` without duplicating its actual write logic. */
    private fun shapeMatches(type: FieldType, raw: Any): Boolean = when (type) {
        FieldType.MONEY_CENTS, FieldType.REFERENCE, FieldType.DATE, FieldType.DATETIME -> raw is Number
        FieldType.NUMBER, FieldType.RATING -> raw is Number
        FieldType.BOOLEAN -> raw is Boolean
        FieldType.MULTI_SELECT_CHOICE -> raw is List<*>
        FieldType.TEXT, FieldType.CHOICE, FieldType.PHOTO, FieldType.LOCATION -> true // any non-null value stringifies safely
        FieldType.COMPUTED -> true // unreachable - filtered out by validate() before this is called
    }
}
