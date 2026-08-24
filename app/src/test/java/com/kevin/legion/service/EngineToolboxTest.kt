package com.kevin.legion.service

import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordType
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * DB-backed coverage for the six directly-dispatched aspect-engine meta-tools
 * (`.scratch/aspect-engine/issues/17-build-voice-surface.md`) - `list_aspects`, `describe_aspect`,
 * `query_records`, `create_record`, `update_record`, `delete_record`. Robolectric through the real
 * [CarDatabase.getDatabase] path, same shape as [com.kevin.legion.engine.RecordStoreTest].
 * `aspect_clerk`/`create_aspect`/`update_aspect` are deliberately NOT exercised end-to-end here -
 * all three make a real Gemini call, and this suite must stay green with no key present
 * (`./gradlew testDebugUnitTest -Pnokey`); their pure logic is covered separately in
 * [EngineToolboxClerkResultTest] and [EngineToolboxDraftHandshakeTest].
 */
@RunWith(RobolectricTestRunner::class)
class EngineToolboxTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private suspend fun seedAspect(): Triple<Long, Long, Long> {
        val now = System.currentTimeMillis()
        val aspectId = db.aspectDao().insert(Aspect(name = "Workouts", createdAt = now, updatedAt = now))
        val recordTypeId = db.recordTypeDao().insert(
            RecordType(aspectId = aspectId, name = "Set", createdAt = now, updatedAt = now),
        )
        val exerciseFieldId = db.fieldDefDao().insert(
            FieldDef(recordTypeId = recordTypeId, name = "exercise", type = FieldType.TEXT, createdAt = now, updatedAt = now),
        )
        db.fieldDefDao().insert(
            FieldDef(recordTypeId = recordTypeId, name = "weight", type = FieldType.NUMBER, createdAt = now, updatedAt = now),
        )
        return Triple(aspectId, recordTypeId, exerciseFieldId)
    }

    // ---- list_aspects / describe_aspect -----------------------------------------------------------

    @Test
    fun `list_aspects reports the seeded aspect and its record type`() = runBlocking {
        seedAspect()
        val out = EngineToolbox.dispatch(context, "list_aspects", JSONObject())!!
        val aspects = out.getJSONArray("aspects")
        assertEquals(1, aspects.length())
        assertEquals("Workouts", aspects.getJSONObject(0).getString("name"))
        assertEquals("Set", aspects.getJSONObject(0).getJSONArray("recordTypes").getString(0))
    }

    @Test
    fun `describe_aspect reports every field with its type`() = runBlocking {
        seedAspect()
        val out = EngineToolbox.dispatch(context, "describe_aspect", JSONObject().put("aspectName", "workouts"))!!
        val recordTypes = out.getJSONArray("recordTypes")
        val fields = recordTypes.getJSONObject(0).getJSONArray("fields")
        assertEquals(2, fields.length())
        assertEquals("exercise", fields.getJSONObject(0).getString("name"))
        assertEquals("TEXT", fields.getJSONObject(0).getString("type"))
    }

    @Test
    fun `describe_aspect on an unknown aspect fails in words`() = runBlocking {
        val out = EngineToolbox.dispatch(context, "describe_aspect", JSONObject().put("aspectName", "nope"))!!
        assertFalse(out.getBoolean("success"))
        assertTrue(out.getString("message").contains("nope"))
    }

    // ---- create_record / update_record -----------------------------------------------------------

    @Test
    fun `create_record writes a row and returns its id`() = runBlocking {
        seedAspect()
        val args = JSONObject()
            .put("aspectName", "Workouts")
            .put("recordTypeName", "Set")
            .put("fields", JSONObject().put("exercise", "bench").put("weight", 185))
        val out = EngineToolbox.dispatch(context, "create_record", args)!!
        assertTrue(out.getBoolean("success"))

        val all = db.engineRecordDao().activeByRecordType(db.recordTypeDao().listByAspect(1).first().id)
        assertEquals(1, all.size)
    }

    @Test
    fun `create_record refuses an unknown field name and writes nothing`() = runBlocking {
        seedAspect()
        val args = JSONObject()
            .put("aspectName", "Workouts")
            .put("recordTypeName", "Set")
            .put("fields", JSONObject().put("notAField", "x"))
        val out = EngineToolbox.dispatch(context, "create_record", args)!!
        assertFalse(out.getBoolean("success"))
        assertTrue(out.getString("message").contains("notAField"))

        val recordTypeId = db.recordTypeDao().listByAspect(1).first().id
        assertTrue(db.engineRecordDao().activeByRecordType(recordTypeId).isEmpty())
    }

    @Test
    fun `create_record on an unknown aspect fails in words`() = runBlocking {
        val args = JSONObject().put("aspectName", "nope").put("recordTypeName", "x").put("fields", JSONObject())
        val out = EngineToolbox.dispatch(context, "create_record", args)!!
        assertFalse(out.getBoolean("success"))
        assertTrue(out.getString("message").contains("nope"))
    }

    @Test
    fun `update_record changes only the given field, preserving the rest`() = runBlocking {
        val (_, recordTypeId, _) = seedAspect()
        val createArgs = JSONObject()
            .put("aspectName", "Workouts")
            .put("recordTypeName", "Set")
            .put("fields", JSONObject().put("exercise", "bench").put("weight", 185))
        EngineToolbox.dispatch(context, "create_record", createArgs)
        val recordId = db.engineRecordDao().activeByRecordType(recordTypeId).first().id

        val updateArgs = JSONObject().put("recordId", recordId).put("fields", JSONObject().put("weight", 190))
        val out = EngineToolbox.dispatch(context, "update_record", updateArgs)!!
        assertTrue(out.getBoolean("success"))

        val query = EngineToolbox.dispatch(
            context, "query_records",
            JSONObject().put("aspectName", "Workouts").put("recordTypeName", "Set"),
        )!!
        val record = query.getJSONArray("records").getJSONObject(0).getJSONObject("fields")
        assertEquals("bench", record.getString("exercise"))
        assertEquals(190.0, record.getDouble("weight"), 0.001)
    }

    @Test
    fun `update_record on a missing id fails in words and writes nothing`() = runBlocking {
        val out = EngineToolbox.dispatch(context, "update_record", JSONObject().put("recordId", 999).put("fields", JSONObject()))!!
        assertFalse(out.getBoolean("success"))
        assertTrue(out.getString("message").contains("999"))
    }

    // ---- query_records -------------------------------------------------------------------------

    @Test
    fun `query_records filters by exact field value`() = runBlocking {
        val (_, recordTypeId, _) = seedAspect()
        EngineToolbox.dispatch(
            context, "create_record",
            JSONObject().put("aspectName", "Workouts").put("recordTypeName", "Set")
                .put("fields", JSONObject().put("exercise", "bench").put("weight", 185)),
        )
        EngineToolbox.dispatch(
            context, "create_record",
            JSONObject().put("aspectName", "Workouts").put("recordTypeName", "Set")
                .put("fields", JSONObject().put("exercise", "squat").put("weight", 225)),
        )

        val out = EngineToolbox.dispatch(
            context, "query_records",
            JSONObject().put("aspectName", "Workouts").put("recordTypeName", "Set")
                .put("filters", JSONObject().put("exercise", "squat")),
        )!!
        assertEquals(1, out.getInt("count"))
        assertEquals("squat", out.getJSONArray("records").getJSONObject(0).getJSONObject("fields").getString("exercise"))
        assertTrue(recordTypeId > 0)
    }

    // ---- delete_record: single unconfirmed, bulk confirm-first ------------------------------------

    @Test
    fun `delete_record single id trashes immediately with a spoken receipt`() = runBlocking {
        val (_, recordTypeId, _) = seedAspect()
        EngineToolbox.dispatch(
            context, "create_record",
            JSONObject().put("aspectName", "Workouts").put("recordTypeName", "Set")
                .put("fields", JSONObject().put("exercise", "bench").put("weight", 185)),
        )
        val recordId = db.engineRecordDao().activeByRecordType(recordTypeId).first().id

        val out = EngineToolbox.dispatch(context, "delete_record", JSONObject().put("recordId", recordId))!!
        assertTrue(out.getBoolean("success"))
        assertTrue(out.getString("message").contains("bench"))
        assertTrue(db.engineRecordDao().activeByRecordType(recordTypeId).isEmpty())
    }

    @Test
    fun `delete_record single id on a missing record fails in words`() = runBlocking {
        val out = EngineToolbox.dispatch(context, "delete_record", JSONObject().put("recordId", 999))!!
        assertFalse(out.getBoolean("success"))
        assertTrue(out.getString("message").contains("999"))
    }

    @Test
    fun `delete_record bulk without confirm writes nothing and reports the count`() = runBlocking {
        val (_, recordTypeId, _) = seedAspect()
        EngineToolbox.dispatch(
            context, "create_record",
            JSONObject().put("aspectName", "Workouts").put("recordTypeName", "Set")
                .put("fields", JSONObject().put("exercise", "bench").put("weight", 185)),
        )
        EngineToolbox.dispatch(
            context, "create_record",
            JSONObject().put("aspectName", "Workouts").put("recordTypeName", "Set")
                .put("fields", JSONObject().put("exercise", "bench").put("weight", 195)),
        )

        val args = JSONObject().put("aspectName", "Workouts").put("recordTypeName", "Set")
            .put("filters", JSONObject().put("exercise", "bench"))
        val out = EngineToolbox.dispatch(context, "delete_record", args)!!
        assertTrue(out.getBoolean("success"))
        assertFalse(out.getBoolean("committed"))
        assertEquals(2, out.getInt("matchedCount"))
        // Nothing was actually deleted on the unconfirmed call.
        assertEquals(2, db.engineRecordDao().activeByRecordType(recordTypeId).size)
    }

    @Test
    fun `delete_record bulk with confirm true deletes every match`() = runBlocking {
        val (_, recordTypeId, _) = seedAspect()
        EngineToolbox.dispatch(
            context, "create_record",
            JSONObject().put("aspectName", "Workouts").put("recordTypeName", "Set")
                .put("fields", JSONObject().put("exercise", "bench").put("weight", 185)),
        )
        EngineToolbox.dispatch(
            context, "create_record",
            JSONObject().put("aspectName", "Workouts").put("recordTypeName", "Set")
                .put("fields", JSONObject().put("exercise", "bench").put("weight", 195)),
        )

        val args = JSONObject().put("aspectName", "Workouts").put("recordTypeName", "Set")
            .put("filters", JSONObject().put("exercise", "bench")).put("confirm", true)
        val out = EngineToolbox.dispatch(context, "delete_record", args)!!
        assertTrue(out.getBoolean("success"))
        assertTrue(out.getString("message").contains("2 of 2"))
        assertTrue(db.engineRecordDao().activeByRecordType(recordTypeId).isEmpty())
    }

    @Test
    fun `delete_record bulk with no matches fails and never asks to confirm`() = runBlocking {
        seedAspect()
        val args = JSONObject().put("aspectName", "Workouts").put("recordTypeName", "Set")
            .put("filters", JSONObject().put("exercise", "deadlift"))
        val out = EngineToolbox.dispatch(context, "delete_record", args)!!
        assertFalse(out.getBoolean("success"))
    }
}
