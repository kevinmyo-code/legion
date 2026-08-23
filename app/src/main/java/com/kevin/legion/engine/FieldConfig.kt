package com.kevin.legion.engine

import com.kevin.legion.data.local.DeletePolicy
import com.kevin.legion.data.local.FieldDef
import org.json.JSONArray
import org.json.JSONObject

/** [FieldDef.config]'s [FieldType.REFERENCE] shape - the target record type and what happens to a
 * row holding this reference when the target is deleted (ticket 03 answer point 3). */
data class ReferenceFieldConfig(val targetRecordTypeId: Long, val deletePolicy: DeletePolicy)

/**
 * Reads and writes [FieldDef.config] JSON - the single place every field type's config shape is
 * defined, so [com.kevin.legion.engine.RecordStore], the future generated forms, and the future
 * import gate can never disagree about what a config blob means. [FieldDef] itself stays opaque to
 * this shape (see its own doc comment); nothing outside this file should call `JSONObject(fieldDef.config)`
 * directly.
 */
object FieldConfig {
    private const val KIND = "kind"
    private const val KIND_AGGREGATE = "aggregate"
    private const val KIND_ARITHMETIC = "arithmetic"

    fun choiceOptions(configJson: String?): List<String> {
        if (configJson.isNullOrBlank()) return emptyList()
        val arr = JSONObject(configJson).optJSONArray("options") ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun serializeChoice(options: List<String>): String =
        JSONObject().put("options", JSONArray(options)).toString()

    fun referenceConfig(configJson: String?): ReferenceFieldConfig? {
        if (configJson.isNullOrBlank()) return null
        val obj = JSONObject(configJson)
        if (!obj.has("targetRecordTypeId")) return null
        return ReferenceFieldConfig(
            targetRecordTypeId = obj.getLong("targetRecordTypeId"),
            deletePolicy = DeletePolicy.valueOf(obj.getString("deletePolicy")),
        )
    }

    fun serializeReference(targetRecordTypeId: Long, deletePolicy: DeletePolicy): String =
        JSONObject()
            .put("targetRecordTypeId", targetRecordTypeId)
            .put("deletePolicy", deletePolicy.name)
            .toString()

    fun computedExpression(configJson: String?): ComputedExpression? {
        if (configJson.isNullOrBlank()) return null
        val obj = JSONObject(configJson)
        return when (obj.optString(KIND)) {
            KIND_AGGREGATE -> ComputedExpression.Aggregate(
                childRecordTypeId = obj.getLong("childRecordTypeId"),
                viaFieldId = obj.getLong("viaFieldId"),
                op = AggregateOp.valueOf(obj.getString("op")),
                sourceFieldId = if (obj.has("sourceFieldId") && !obj.isNull("sourceFieldId")) {
                    obj.getLong("sourceFieldId")
                } else {
                    null
                },
            )
            KIND_ARITHMETIC -> ComputedExpression.Arithmetic(
                leftFieldId = obj.getLong("leftFieldId"),
                op = ArithmeticOp.valueOf(obj.getString("op")),
                rightFieldId = obj.getLong("rightFieldId"),
            )
            else -> null
        }
    }

    fun serializeAggregate(
        childRecordTypeId: Long,
        viaFieldId: Long,
        op: AggregateOp,
        sourceFieldId: Long?,
    ): String {
        val obj = JSONObject()
            .put(KIND, KIND_AGGREGATE)
            .put("childRecordTypeId", childRecordTypeId)
            .put("viaFieldId", viaFieldId)
            .put("op", op.name)
        if (sourceFieldId != null) obj.put("sourceFieldId", sourceFieldId)
        return obj.toString()
    }

    fun serializeArithmetic(leftFieldId: Long, op: ArithmeticOp, rightFieldId: Long): String =
        JSONObject()
            .put(KIND, KIND_ARITHMETIC)
            .put("leftFieldId", leftFieldId)
            .put("op", op.name)
            .put("rightFieldId", rightFieldId)
            .toString()
}
