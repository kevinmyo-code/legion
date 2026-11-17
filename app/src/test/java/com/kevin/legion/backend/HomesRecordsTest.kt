package com.kevin.legion.backend

import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.RecordType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine-brain ticket 15: the user-aspect records as engine rows and back, as plain JVM rules. No
 * local id leaves the phone, a plugin's aspect never travels, and an id the phone cannot name is
 * kept, never dropped. Invented rows.
 */
class HomesRecordsTest {
    private val hikes = Aspect(id = 1, name = "Hikes", createdAt = 100, updatedAt = 100)
    private val fleet = Aspect(id = 2, name = "Fleet", createdAt = 50, updatedAt = 50)
    private val hike = RecordType(
        id = 10, aspectId = 1, name = "Hike", primaryAmountFieldId = 102, createdAt = 100, updatedAt = 100,
    )
    private val photo = RecordType(id = 11, aspectId = 1, name = "Photo", createdAt = 100, updatedAt = 100)
    private val vehicle = RecordType(id = 20, aspectId = 2, name = "Vehicle", createdAt = 50, updatedAt = 50)
    private val trail = FieldDef(
        id = 101, recordTypeId = 10, name = "trail", type = FieldType.TEXT, createdAt = 100, updatedAt = 100,
    )
    private val parking = FieldDef(
        id = 102, recordTypeId = 10, name = "parking", type = FieldType.MONEY_CENTS, createdAt = 100, updatedAt = 100,
    )
    private val ref = FieldDef(
        id = 103, recordTypeId = 11, name = "hike", type = FieldType.REFERENCE,
        config = """{"targetRecordTypeId":10,"deletePolicy":"BLOCK"}""", createdAt = 100, updatedAt = 100,
    )
    private val plate = FieldDef(
        id = 201, recordTypeId = 20, name = "plate", type = FieldType.TEXT, ownerPluginId = "fleet", locked = true,
        createdAt = 50, updatedAt = 50,
    )
    private val walk = EngineRecord(
        id = 1000, recordTypeId = 10, createdAt = 200, updatedAt = 300, amountCents = 500,
        provenance = RecordProvenance.USER, payload = """{"101":"Test ridge","102":500}""", guid = "rec-walk",
    )
    private val snap = EngineRecord(
        id = 1001, recordTypeId = 11, createdAt = 200, updatedAt = 300, provenance = RecordProvenance.USER,
        payload = """{"103":1000,"999":"orphan"}""", guid = "rec-snap", deletedAt = 400,
    )
    private val all = listOf(trail, parking, ref, plate)
    private val types = all.associate { it.id to it.type }

    private fun book(remembered: Map<String, String> = emptyMap()) =
        HomesRecords.IdBook(listOf(hikes, fleet), listOf(hike, photo, vehicle), all, listOf(walk, snap), remembered)

    @Test
    fun onlyAspectsNoPluginOwnsTravel() {
        assertEquals(setOf(1L), HomesRecords.userAspectIds(listOf(hikes, fleet), listOf(hike, photo, vehicle), all))
        // By name too: a seeded aspect whose fields carry no owner still never travels.
        val unowned = all.map { it.copy(ownerPluginId = null) }
        assertEquals(setOf(1L), HomesRecords.userAspectIds(listOf(hikes, fleet), listOf(hike, photo, vehicle), unowned))
    }

    @Test
    fun aSchemaRowsGuidIsStableAcrossARenameAndARememberedGuidWins() {
        val before = book().aspectGuid.getValue(1)
        val renamed = HomesRecords.IdBook(
            listOf(hikes.copy(name = "Walks", updatedAt = 999)), emptyList(), emptyList(), emptyList(), emptyMap(),
        )
        assertEquals(before, renamed.aspectGuid.getValue(1))
        assertEquals(before, HomesRecords.derived(HomesRecords.ASPECT, 1, 100))
        assertNotEquals(before, HomesRecords.derived(HomesRecords.ASPECT, 1, 101))
        assertNotEquals(before, HomesRecords.derived(HomesRecords.RECORD_TYPE, 1, 100))
        assertEquals("engine-a", book(mapOf("aspect:1:100" to "engine-a")).aspectGuid.getValue(1))
        // A remembered guid for an older row that had the same local id never lands on this one.
        assertEquals(before, book(mapOf("aspect:1:99" to "engine-old")).aspectGuid.getValue(1))
    }

    @Test
    fun aRecordTravelsWithGuidsNotLocalIds() {
        val ids = book()
        val row = HomesRecords.record(walk, ids, types)
        assertEquals("rec-walk", row.getValue("origin_guid").jsonPrimitive.content)
        assertEquals(ids.recordTypeGuid.getValue(10), row.getValue("record_type").jsonPrimitive.content)
        val payload = row.getValue("payload").jsonObject
        assertEquals(setOf(ids.fieldGuid.getValue(101), ids.fieldGuid.getValue(102)), payload.keys)
        assertEquals("Test ridge", payload.getValue(ids.fieldGuid.getValue(101)).jsonPrimitive.content)
        assertEquals(JsonNull, row["trashed_at"])
        assertEquals("USER", row.getValue("provenance").jsonPrimitive.content)
    }

    @Test
    fun aReferenceBecomesTheTargetsGuidAndAnUnknownFieldIsKeptNotDropped() {
        val ids = book()
        val row = HomesRecords.record(snap, ids, types)
        val payload = row.getValue("payload").jsonObject
        assertEquals("rec-walk", payload.getValue(ids.fieldGuid.getValue(103)).jsonPrimitive.content)
        assertEquals("orphan", payload.getValue("phone_field:999").jsonPrimitive.content)
        assertTrue(row.getValue("trashed_at").jsonPrimitive.content.startsWith("1970-01-01T00:00:00.4"))
        // And back: the same ids, the reference the same record.
        val back = Json.parseToJsonElement(HomesRecords.payloadIn(payload, ids, types)!!).jsonObject
        assertEquals(1000L, back.getValue("103").jsonPrimitive.content.toLong())
        assertEquals("orphan", back.getValue("999").jsonPrimitive.content)
    }

    @Test
    fun configAndPrimaryFieldsTravelByGuidAndComeBackAsIds() {
        val ids = book()
        val field = HomesRecords.fieldDef(ref, ids)
        val config = Json.parseToJsonElement(field.getValue("config").jsonPrimitive.content).jsonObject
        assertEquals(ids.recordTypeGuid.getValue(10), config.getValue("targetRecordType").jsonPrimitive.content)
        assertEquals("BLOCK", config.getValue("deletePolicy").jsonPrimitive.content)
        val back = Json.parseToJsonElement(HomesRecords.configIn(config.toString(), ids)!!).jsonObject
        assertEquals(10L, back.getValue("targetRecordTypeId").jsonPrimitive.content.toLong())
        val computed = """{"kind":"arithmetic","leftFieldId":101,"op":"PLUS","rightFieldId":102}"""
        val out = Json.parseToJsonElement(HomesRecords.configOut(computed, ids)!!).jsonObject
        assertEquals(ids.fieldGuid.getValue(101), out.getValue("leftField").jsonPrimitive.content)
        val type = HomesRecords.recordType(hike, ids)
        assertEquals(ids.fieldGuid.getValue(102), type.getValue("primary_amount_field").jsonPrimitive.content)
        assertEquals(ids.aspectGuid.getValue(1), type.getValue("aspect").jsonPrimitive.content)
        assertEquals("""{"options":["a","b"]}""", HomesRecords.configOut("""{"options":["a","b"]}""", ids))
    }

    @Test
    fun anEnginePayloadBecomesTheStoresValuesOrNothingWhenItNamesAnUnknownField() {
        val ids = book()
        val payload = JsonObject(
            mapOf(
                ids.fieldGuid.getValue(101) to JsonPrimitive("Test creek"),
                ids.fieldGuid.getValue(102) to JsonPrimitive(250),
            ),
        )
        val values = HomesRecords.storeValues(payload, ids, listOf(trail, parking))!!
        assertEquals("Test creek", values[101])
        assertEquals(250L, values[102])
        val reference = JsonObject(mapOf(ids.fieldGuid.getValue(103) to JsonPrimitive("rec-walk")))
        assertEquals(1000L, HomesRecords.storeValues(reference, ids, listOf(ref))!![103])
        assertNull(HomesRecords.storeValues(JsonObject(mapOf("nobody" to JsonPrimitive(1))), ids, listOf(trail)))
    }
}
