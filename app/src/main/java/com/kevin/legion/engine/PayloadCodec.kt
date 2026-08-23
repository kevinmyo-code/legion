package com.kevin.legion.engine

import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads and writes one field's value into [com.kevin.legion.data.local.EngineRecord.payload] JSON,
 * keyed by [FieldDef.id] (as a string - JSON object keys are always strings). Centralized so every
 * write path - [RecordStore] today, the generated forms and the import gate later - encodes and
 * decodes a field the exact same way; nothing outside this file should touch a record's payload
 * `JSONObject` directly.
 */
object PayloadCodec {
    fun key(fieldDefId: Long): String = fieldDefId.toString()

    /**
     * Writes [raw] into [payload] under [fieldDef]'s key, coercing to the JSON shape [fieldDef.type]
     * expects. `null` writes an explicit JSON null (not a missing key) so "the driver cleared this
     * field" and "this field predates the record" stay distinguishable if that distinction ever
     * matters to a reader.
     */
    fun write(payload: JSONObject, fieldDef: FieldDef, raw: Any?) {
        val k = key(fieldDef.id)
        if (raw == null) {
            payload.put(k, JSONObject.NULL)
            return
        }
        when (fieldDef.type) {
            FieldType.MULTI_SELECT_CHOICE -> {
                @Suppress("UNCHECKED_CAST")
                val list = (raw as? List<String>) ?: emptyList()
                payload.put(k, JSONArray(list))
            }
            FieldType.MONEY_CENTS, FieldType.REFERENCE, FieldType.DATE, FieldType.DATETIME ->
                payload.put(k, (raw as Number).toLong())
            FieldType.NUMBER, FieldType.RATING -> payload.put(k, (raw as Number).toDouble())
            FieldType.BOOLEAN -> payload.put(k, raw as Boolean)
            FieldType.COMPUTED -> throw IllegalArgumentException(
                "computed field '${fieldDef.name}' is materialized, never written directly - use writeComputed",
            )
            else -> payload.put(k, raw.toString())
        }
    }

    /** Materializes a [ComputedValue] into [payload] - the only path that ever writes a
     * [FieldType.COMPUTED] field's value. [ComputedValue.Error] writes `{"error": "..."}` so a
     * reader can tell an error state apart from any legitimate numeric value at a glance. */
    fun writeComputed(payload: JSONObject, fieldDef: FieldDef, value: ComputedValue) {
        val k = key(fieldDef.id)
        when (value) {
            is ComputedValue.MoneyCents -> payload.put(k, value.cents)
            is ComputedValue.Number -> payload.put(k, value.value)
            is ComputedValue.Count -> payload.put(k, value.count)
            ComputedValue.Empty -> payload.put(k, JSONObject.NULL)
            is ComputedValue.Error -> payload.put(k, JSONObject().put("error", value.message))
        }
    }

    fun readLong(payload: JSONObject, fieldDefId: Long): Long? {
        val k = key(fieldDefId)
        return if (payload.has(k) && !payload.isNull(k)) payload.optLong(k) else null
    }

    fun readDouble(payload: JSONObject, fieldDefId: Long): Double? {
        val k = key(fieldDefId)
        return if (payload.has(k) && !payload.isNull(k)) payload.optDouble(k) else null
    }

    fun readString(payload: JSONObject, fieldDefId: Long): String? {
        val k = key(fieldDefId)
        return if (payload.has(k) && !payload.isNull(k)) payload.optString(k) else null
    }

    /** A [FieldType.REFERENCE] field's stored target id, or null if unset. */
    fun readReferenceId(payload: JSONObject, fieldDefId: Long): Long? = readLong(payload, fieldDefId)

    /** Non-null exactly when the field at [fieldDefId] currently holds a [ComputedValue.Error] -
     * "in words on every surface" (ticket 04 answer point 4) starts with a reader that can find it. */
    fun readComputedError(payload: JSONObject, fieldDefId: Long): String? {
        val k = key(fieldDefId)
        val v = payload.opt(k)
        return if (v is JSONObject && v.has("error")) v.optString("error") else null
    }

    /** Every TEXT/CHOICE/MULTI_SELECT_CHOICE value on [payload], space-joined - the promoted
     * [com.kevin.legion.data.local.EngineRecord.searchText] column's source of truth. */
    fun buildSearchText(fieldDefs: List<FieldDef>, payload: JSONObject): String =
        fieldDefs
            .filter { it.type == FieldType.TEXT || it.type == FieldType.CHOICE || it.type == FieldType.MULTI_SELECT_CHOICE }
            .mapNotNull { fd ->
                when (val v = payload.opt(key(fd.id))) {
                    null, JSONObject.NULL -> null
                    is JSONArray -> (0 until v.length()).joinToString(" ") { v.optString(it) }
                    else -> v.toString()
                }
            }
            .filter { it.isNotBlank() }
            .joinToString(" ")
}
