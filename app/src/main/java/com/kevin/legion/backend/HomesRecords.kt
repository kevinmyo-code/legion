package com.kevin.legion.backend

import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordType
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The user-aspect records (engine-brain ticket 15) as engine rows and back: the phone's aspect
 * engine ([Aspect], [RecordType], [FieldDef], [EngineRecord]) translated so no local Room id
 * leaves the phone. Pure: no Context, no Room, no network, so every rule is a plain JVM test.
 *
 * - **Identity.** A record travels under its own [EngineRecord.guid]. The three schema tables have
 *   no guid column (Room is frozen at v80, and a column would be a migration), so [IdBook] gives
 *   each row one: the guid the engine gave it when the engine wrote it (remembered), else a
 *   name-based uuid of kind, local id and creation instant ([derived]). A rename keeps it; a
 *   restored database keeps it (same ids, same instants); a pull re-learns a forgotten one by
 *   matching the engine row's creation instant and name ([HomesRecordsSync]).
 * - **Every local id is translated**: the parent columns, a record type's two primary-field
 *   pointers, payload keys (field ids), REFERENCE values (record ids) and the ids inside a field's
 *   `config`. One the phone cannot name is kept as `phone_field:<id>` / `phone_record:<id>`, never
 *   dropped, and comes back as the same id.
 * - **Only user aspects travel** ([userAspectIds]): an aspect a capability plugin seeded (its
 *   fields carry `ownerPluginId`) holds replica copies of rows with typed homes on the engine.
 */
object HomesRecords {
    const val ASPECT = "aspect"
    const val RECORD_TYPE = "record_type"
    const val FIELD_DEF = "field_def"
    private const val PHONE_FIELD = "phone_field:"
    private const val PHONE_RECORD = "phone_record:"

    /** `config` keys that hold a local id, and the guid-keyed name each travels under. */
    private val CONFIG_RECORD_TYPE_KEYS = mapOf(
        "targetRecordTypeId" to "targetRecordType",
        "childRecordTypeId" to "childRecordType",
    )
    private val CONFIG_FIELD_KEYS = mapOf(
        "viaFieldId" to "viaField",
        "sourceFieldId" to "sourceField",
        "leftFieldId" to "leftField",
        "rightFieldId" to "rightField",
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun derived(kind: String, localId: Long, createdAt: Long): String =
        UUID.nameUUIDFromBytes("$kind|$localId|$createdAt".toByteArray(Charsets.UTF_8)).toString()

    /**
     * The guid of each schema row. [remembered] maps `"<kind>:<localId>"` to the guid the engine
     * gave a row it wrote; everything else is [derived]. Built from EVERY local row (plugin ones
     * too), so a reference into a plugin record type still translates.
     */
    class IdBook(
        aspects: List<Aspect>,
        recordTypes: List<RecordType>,
        fieldDefs: List<FieldDef>,
        records: List<EngineRecord>,
        val remembered: Map<String, String>,
    ) {
        private fun guid(kind: String, id: Long, createdAt: Long) = remembered["$kind:$id"] ?: derived(kind, id, createdAt)

        val aspectGuid: Map<Long, String> = aspects.associate { it.id to guid(ASPECT, it.id, it.createdAt) }
        val recordTypeGuid: Map<Long, String> = recordTypes.associate { it.id to guid(RECORD_TYPE, it.id, it.createdAt) }
        val fieldGuid: Map<Long, String> = fieldDefs.associate { it.id to guid(FIELD_DEF, it.id, it.createdAt) }
        val recordGuid: Map<Long, String> = records.associate { it.id to it.guid }

        val aspectId: Map<String, Long> = aspectGuid.entries.associate { (k, v) -> v to k }
        val recordTypeId: Map<String, Long> = recordTypeGuid.entries.associate { (k, v) -> v to k }
        val fieldId: Map<String, Long> = fieldGuid.entries.associate { (k, v) -> v to k }
        val recordId: Map<String, Long> = recordGuid.entries.associate { (k, v) -> v to k }
    }

    /** The aspects no capability plugin owns: none of their fields carries an `ownerPluginId`. */
    fun userAspectIds(aspects: List<Aspect>, recordTypes: List<RecordType>, fieldDefs: List<FieldDef>): Set<Long> {
        val typeAspect = recordTypes.associate { it.id to it.aspectId }
        val pluginAspects = fieldDefs.filter { it.ownerPluginId != null }.mapNotNull { typeAspect[it.recordTypeId] }.toSet()
        return aspects.map { it.id }.filter { it !in pluginAspects }.toSet()
    }

    // ---- Room -> engine ----------------------------------------------------------------------

    fun aspect(row: Aspect, ids: IdBook): JsonObject = buildJsonObject {
        put(HomesRows.ORIGIN_GUID, ids.aspectGuid.getValue(row.id))
        put("name", row.name)
        put("icon", row.icon)
        put("color", row.color)
        put("position", row.position)
        put("archived", row.archived)
        put("archived_at", row.archivedAt?.let(HomesRows::iso))
        put("created_at_client", HomesRows.iso(row.createdAt))
        put("updated_at_client", HomesRows.iso(row.updatedAt))
    }

    fun recordType(row: RecordType, ids: IdBook): JsonObject = buildJsonObject {
        put(HomesRows.ORIGIN_GUID, ids.recordTypeGuid.getValue(row.id))
        put("aspect", ids.aspectGuid[row.aspectId])
        put("name", row.name)
        put("primary_amount_field", row.primaryAmountFieldId?.let { ids.fieldGuid[it] })
        put("primary_due_date_field", row.primaryDueDateFieldId?.let { ids.fieldGuid[it] })
        put("created_at_client", HomesRows.iso(row.createdAt))
        put("updated_at_client", HomesRows.iso(row.updatedAt))
    }

    fun fieldDef(row: FieldDef, ids: IdBook): JsonObject = buildJsonObject {
        put(HomesRows.ORIGIN_GUID, ids.fieldGuid.getValue(row.id))
        put("record_type", ids.recordTypeGuid[row.recordTypeId])
        put("name", row.name)
        put("type", row.type.name)
        put("required", row.required)
        put("position", row.position)
        put("config", configOut(row.config, ids))
        put("created_at_client", HomesRows.iso(row.createdAt))
        put("updated_at_client", HomesRows.iso(row.updatedAt))
    }

    /** [fieldTypes] is every field's type by local id, so a REFERENCE value is told apart. */
    fun record(row: EngineRecord, ids: IdBook, fieldTypes: Map<Long, FieldType>): JsonObject = buildJsonObject {
        put(HomesRows.ORIGIN_GUID, row.guid)
        put("record_type", ids.recordTypeGuid[row.recordTypeId])
        put("due_at", row.dueAt?.let(HomesRows::iso))
        put("amount_cents", row.amountCents)
        put("search_text", row.searchText)
        put("provenance", row.provenance.name)
        put("payload", payloadOut(row.payload, ids, fieldTypes))
        put("created_at_client", HomesRows.iso(row.createdAt))
        put("updated_at_client", HomesRows.iso(row.updatedAt))
        put("trashed_at", row.deletedAt?.let(HomesRows::iso))
    }

    private fun parseObject(text: String?): JsonObject? =
        text?.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    fun payloadOut(payload: String, ids: IdBook, fieldTypes: Map<Long, FieldType>): JsonObject {
        val src = parseObject(payload) ?: return JsonObject(emptyMap())
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in src) {
            val fieldId = key.toLongOrNull()
            val guid = fieldId?.let { ids.fieldGuid[it] }
            val outKey = guid ?: "$PHONE_FIELD$key"
            out[outKey] = if (fieldId != null && fieldTypes[fieldId] == FieldType.REFERENCE) referenceOut(value, ids) else value
        }
        return JsonObject(out)
    }

    private fun referenceOut(value: JsonElement, ids: IdBook): JsonElement {
        val id = (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.longOrNull ?: return value
        return JsonPrimitive(ids.recordGuid[id] ?: "$PHONE_RECORD$id")
    }

    fun configOut(config: String?, ids: IdBook): String? {
        val src = parseObject(config) ?: return config
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in src) {
            val id = (value as? JsonPrimitive)?.longOrNull
            when {
                key in CONFIG_RECORD_TYPE_KEYS && id != null ->
                    out[CONFIG_RECORD_TYPE_KEYS.getValue(key)] = JsonPrimitive(ids.recordTypeGuid[id] ?: "$PHONE_RECORD$id")
                key in CONFIG_FIELD_KEYS && id != null ->
                    out[CONFIG_FIELD_KEYS.getValue(key)] = JsonPrimitive(ids.fieldGuid[id] ?: "$PHONE_FIELD$id")
                else -> out[key] = value
            }
        }
        return JsonObject(out).toString()
    }

    // ---- engine -> Room ----------------------------------------------------------------------

    /** The engine's guid-keyed payload as the phone's id-keyed payload text, or null when it names
     * a field this phone does not hold (the caller skips that row and counts it). */
    fun payloadIn(payload: JsonObject?, ids: IdBook, fieldTypes: Map<Long, FieldType>): String? {
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in payload ?: JsonObject(emptyMap())) {
            val fieldId = ids.fieldId[key] ?: key.removePrefix(PHONE_FIELD).takeIf { key.startsWith(PHONE_FIELD) }?.toLongOrNull()
                ?: return null
            out[fieldId.toString()] = if (fieldTypes[fieldId] == FieldType.REFERENCE) referenceIn(value, ids) else value
        }
        return JsonObject(out).toString()
    }

    private fun referenceIn(value: JsonElement, ids: IdBook): JsonElement {
        val text = (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull ?: return JsonNull
        val id = ids.recordId[text] ?: text.removePrefix(PHONE_RECORD).takeIf { text.startsWith(PHONE_RECORD) }?.toLongOrNull()
        return id?.let(::JsonPrimitive) ?: JsonNull
    }

    fun configIn(config: String?, ids: IdBook): String? {
        val src = parseObject(config) ?: return config
        val out = LinkedHashMap<String, JsonElement>()
        val typeKeys = CONFIG_RECORD_TYPE_KEYS.entries.associate { (k, v) -> v to k }
        val fieldKeys = CONFIG_FIELD_KEYS.entries.associate { (k, v) -> v to k }
        for ((key, value) in src) {
            val text = (value as? JsonPrimitive)?.contentOrNull
            when {
                key in typeKeys && text != null ->
                    localId(text, ids.recordTypeId, PHONE_RECORD)?.let { out[typeKeys.getValue(key)] = JsonPrimitive(it) }
                key in fieldKeys && text != null ->
                    localId(text, ids.fieldId, PHONE_FIELD)?.let { out[fieldKeys.getValue(key)] = JsonPrimitive(it) }
                else -> out[key] = value
            }
        }
        return JsonObject(out).toString()
    }

    private fun localId(text: String, byGuid: Map<String, Long>, prefix: String): Long? =
        byGuid[text] ?: text.removePrefix(prefix).takeIf { text.startsWith(prefix) }?.toLongOrNull()

    /** A guid-keyed payload as the raw values [com.kevin.legion.engine.RecordStore] takes, keyed by
     * local field id: the shapes `PayloadCodec.write` expects per [FieldType]. COMPUTED fields are
     * left out (the store materializes them). Null when a key names no local field. */
    fun storeValues(payload: JsonObject?, ids: IdBook, fields: List<FieldDef>): Map<Long, Any?>? {
        val byId = fields.associateBy { it.id }
        val out = mutableMapOf<Long, Any?>()
        for ((key, value) in payload ?: JsonObject(emptyMap())) {
            val id = ids.fieldId[key] ?: key.removePrefix(PHONE_FIELD).takeIf { key.startsWith(PHONE_FIELD) }?.toLongOrNull()
                ?: return null
            val field = byId[id] ?: return null
            if (field.type == FieldType.COMPUTED) continue
            out[id] = storeValue(field.type, value, ids)
        }
        return out
    }

    private fun storeValue(type: FieldType, value: JsonElement, ids: IdBook): Any? {
        if (value is JsonNull) return null
        if (value is JsonArray) return value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val prim = value as? JsonPrimitive ?: return null
        return when (type) {
            FieldType.MONEY_CENTS, FieldType.DATE, FieldType.DATETIME -> prim.contentOrNull?.toDoubleOrNull()?.toLong()
            FieldType.REFERENCE -> (referenceIn(prim, ids) as? JsonPrimitive)?.longOrNull
            FieldType.NUMBER, FieldType.RATING -> prim.contentOrNull?.toDoubleOrNull()
            FieldType.BOOLEAN -> prim.contentOrNull?.toBooleanStrictOrNull()
            FieldType.MULTI_SELECT_CHOICE -> listOfNotNull(prim.contentOrNull)
            else -> prim.contentOrNull
        }
    }
}
