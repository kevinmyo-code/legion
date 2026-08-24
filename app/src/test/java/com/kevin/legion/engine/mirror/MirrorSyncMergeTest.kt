package com.kevin.legion.engine.mirror

import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.RecordType
import com.kevin.legion.engine.FieldConfig
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Merge-semantics coverage for [MirrorSync] (ticket 20 item 6: "merge semantics (latest-updatedAt
 * wins, local-newer survives, remote-delete trashes...) all through a fake store"). "Fake store"
 * here means exactly what [RecordStoreTest] already establishes for the engine itself: a real,
 * Robolectric-backed [CarDatabase]/[RecordStore], with hand-built [MirrorCodec.ParsedRecordRow]
 * values standing in for what [MirrorCodec] would have parsed out of a real xlsx - there is no SAF,
 * no [MirrorStore], no real Drive folder anywhere in this file. [mergeRecordSheet] itself is marked
 * `internal` for exactly this reason: it is the one piece of [MirrorSync] worth exercising directly,
 * without going through [MirrorSync.exportNow]'s SAF-dependent orchestration.
 */
@RunWith(RobolectricTestRunner::class)
class MirrorSyncMergeTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)
    private val store get() = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
    private val sync get() = MirrorSync(context)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private suspend fun aspect(): Long {
        val now = System.currentTimeMillis()
        return db.aspectDao().insert(Aspect(name = "Test", createdAt = now, updatedAt = now))
    }

    private suspend fun recordType(aspectId: Long, name: String = "Item"): Long {
        val now = System.currentTimeMillis()
        return db.recordTypeDao().insert(RecordType(aspectId = aspectId, name = name, createdAt = now, updatedAt = now))
    }

    private suspend fun textField(recordTypeId: Long, name: String = "Note"): Long {
        val now = System.currentTimeMillis()
        return db.fieldDefDao().insert(
            FieldDef(recordTypeId = recordTypeId, name = name, type = FieldType.TEXT, createdAt = now, updatedAt = now),
        )
    }

    private suspend fun choiceField(recordTypeId: Long, options: List<String>): Long {
        val now = System.currentTimeMillis()
        return db.fieldDefDao().insert(
            FieldDef(
                recordTypeId = recordTypeId, name = "Status", type = FieldType.CHOICE,
                config = FieldConfig.serializeChoice(options), createdAt = now, updatedAt = now,
            ),
        )
    }

    private fun row(
        recordId: Long?,
        updatedAt: Long?,
        fieldValues: Map<Long, Any?>,
        parseErrors: Map<Long, String> = emptyMap(),
    ) = MirrorCodec.ParsedRecordRow(
        rowNumber = 1,
        recordId = recordId,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        provenance = null,
        provenanceReadOnly = false,
        fieldValues = fieldValues,
        fieldParseErrors = parseErrors,
        unmappedHeaders = emptyList(),
    )

    // ---- create --------------------------------------------------------------------------------

    @Test
    fun `a row with no id and real content is created`() = runBlocking {
        val aspectId = aspect()
        val typeId = recordType(aspectId)
        val fieldId = textField(typeId)
        val fieldDefs = db.fieldDefDao().forRecordType(typeId)

        val sheet = MirrorCodec.ParsedRecordSheet(typeId, "Item", listOf(row(null, null, mapOf(fieldId to "hello"))))
        val outcome = sync.mergeRecordSheet(typeId, fieldDefs, sheet, MirrorStateStore.AspectSyncState())

        assertEquals(1, outcome.created)
        assertEquals(0, outcome.updated)
        assertTrue(outcome.quarantined.isEmpty())
        val stored = db.engineRecordDao().activeByRecordType(typeId)
        assertEquals(1, stored.size)
    }

    // ---- update: file newer wins ------------------------------------------------------------------

    @Test
    fun `a row whose stated updatedAt is newer than local wins and updates`() = runBlocking {
        val aspectId = aspect()
        val typeId = recordType(aspectId)
        val fieldId = textField(typeId)
        val fieldDefs = db.fieldDefDao().forRecordType(typeId)
        val id = (store.create(typeId, mapOf(fieldId to "old"), RecordProvenance.USER) as RecordStore.WriteResult.Success).recordId
        val existing = db.engineRecordDao().getById(id)!!

        val sheet = MirrorCodec.ParsedRecordSheet(typeId, "Item", listOf(row(id, existing.updatedAt + 10_000, mapOf(fieldId to "new"))))
        val outcome = sync.mergeRecordSheet(typeId, fieldDefs, sheet, MirrorStateStore.AspectSyncState())

        assertEquals(1, outcome.updated)
        assertEquals(0, outcome.created)
        assertTrue(outcome.quarantined.isEmpty())
        val updated = db.engineRecordDao().getById(id)!!
        assertEquals("new", com.kevin.legion.engine.PayloadCodec.readString(org.json.JSONObject(updated.payload), fieldId))
    }

    // ---- unchanged: identical content is a no-op regardless of stated timestamp -----------------

    @Test
    fun `identical content is a no-op even when the file's stated updatedAt is older`() = runBlocking {
        val aspectId = aspect()
        val typeId = recordType(aspectId)
        val fieldId = textField(typeId)
        val fieldDefs = db.fieldDefDao().forRecordType(typeId)
        val id = (store.create(typeId, mapOf(fieldId to "same"), RecordProvenance.USER) as RecordStore.WriteResult.Success).recordId
        val existing = db.engineRecordDao().getById(id)!!

        val sheet = MirrorCodec.ParsedRecordSheet(typeId, "Item", listOf(row(id, existing.updatedAt - 5_000, mapOf(fieldId to "same"))))
        val outcome = sync.mergeRecordSheet(typeId, fieldDefs, sheet, MirrorStateStore.AspectSyncState())

        assertEquals(0, outcome.updated)
        assertEquals(0, outcome.created)
        assertEquals(1, outcome.unchanged)
        val after = db.engineRecordDao().getById(id)!!
        assertEquals(existing.updatedAt, after.updatedAt) // untouched
    }

    // ---- local-newer-since-export survives a stale hand edit --------------------------------------

    @Test
    fun `a local record edited after the last export survives a stale file-side edit`() = runBlocking {
        val aspectId = aspect()
        val typeId = recordType(aspectId)
        val fieldId = textField(typeId)
        val fieldDefs = db.fieldDefDao().forRecordType(typeId)
        val id = (store.create(typeId, mapOf(fieldId to "exported-value"), RecordProvenance.USER) as RecordStore.WriteResult.Success).recordId
        val afterCreate = db.engineRecordDao().getById(id)!!
        val exportStamp = afterCreate.updatedAt - 1 // pretend the export happened just BEFORE this create
        // Now the local record is edited again, AFTER the export stamp:
        store.update(id, mapOf(fieldId to "local-edit-after-export"), afterCreate.updatedAt + 1_000)
        val afterLocalEdit = db.engineRecordDao().getById(id)!!

        // The file row still carries the STALE exported content, with no timestamp advance (a plain
        // hand edit never bumps updatedAt) - this is exactly the "content differs, timestamp doesn't
        // help" case this class's own doc comment describes.
        val sheet = MirrorCodec.ParsedRecordSheet(typeId, "Item", listOf(row(id, afterCreate.updatedAt, mapOf(fieldId to "exported-value"))))
        val outcome = sync.mergeRecordSheet(
            typeId, fieldDefs, sheet,
            MirrorStateStore.AspectSyncState(lastExportAt = exportStamp),
        )

        assertEquals(0, outcome.updated)
        assertEquals(1, outcome.unchanged) // local wins, file content left unapplied
        val after = db.engineRecordDao().getById(id)!!
        assertEquals("local-edit-after-export", com.kevin.legion.engine.PayloadCodec.readString(org.json.JSONObject(after.payload), fieldId))
        assertEquals(afterLocalEdit.updatedAt, after.updatedAt)
    }

    // ---- reconciled rows are rejected as read-only -------------------------------------------------

    @Test
    fun `a DETERMINISTIC row with different content is quarantined, never applied`() = runBlocking {
        val aspectId = aspect()
        val typeId = recordType(aspectId)
        val fieldId = textField(typeId)
        val fieldDefs = db.fieldDefDao().forRecordType(typeId)
        val id = (store.create(typeId, mapOf(fieldId to "reconciled-value"), RecordProvenance.DETERMINISTIC) as RecordStore.WriteResult.Success).recordId
        val existing = db.engineRecordDao().getById(id)!!

        val sheet = MirrorCodec.ParsedRecordSheet(typeId, "Item", listOf(row(id, existing.updatedAt + 10_000, mapOf(fieldId to "hand-edited-value"))))
        val outcome = sync.mergeRecordSheet(typeId, fieldDefs, sheet, MirrorStateStore.AspectSyncState())

        assertEquals(0, outcome.updated)
        assertEquals(1, outcome.quarantined.size)
        assertTrue(outcome.quarantined.single().contains("read-only"))
        val after = db.engineRecordDao().getById(id)!!
        assertEquals("reconciled-value", com.kevin.legion.engine.PayloadCodec.readString(org.json.JSONObject(after.payload), fieldId))
    }

    @Test
    fun `a DETERMINISTIC row with UNCHANGED content is a silent no-op, not a quarantine`() = runBlocking {
        val aspectId = aspect()
        val typeId = recordType(aspectId)
        val fieldId = textField(typeId)
        val fieldDefs = db.fieldDefDao().forRecordType(typeId)
        val id = (store.create(typeId, mapOf(fieldId to "value"), RecordProvenance.DETERMINISTIC) as RecordStore.WriteResult.Success).recordId
        val existing = db.engineRecordDao().getById(id)!!

        val sheet = MirrorCodec.ParsedRecordSheet(typeId, "Item", listOf(row(id, existing.updatedAt, mapOf(fieldId to "value"))))
        val outcome = sync.mergeRecordSheet(typeId, fieldDefs, sheet, MirrorStateStore.AspectSyncState())

        assertEquals(1, outcome.unchanged)
        assertTrue(outcome.quarantined.isEmpty())
    }

    // ---- gate: field parse errors and illegal choice values quarantine the row -------------------

    @Test
    fun `a row with a field parse error is quarantined and never written`() = runBlocking {
        val aspectId = aspect()
        val typeId = recordType(aspectId)
        val fieldId = textField(typeId)
        val fieldDefs = db.fieldDefDao().forRecordType(typeId)

        val sheet = MirrorCodec.ParsedRecordSheet(
            typeId, "Item",
            listOf(row(null, null, emptyMap(), parseErrors = mapOf(fieldId to "'Note' needs text, got a formula"))),
        )
        val outcome = sync.mergeRecordSheet(typeId, fieldDefs, sheet, MirrorStateStore.AspectSyncState())

        assertEquals(0, outcome.created)
        assertEquals(1, outcome.quarantined.size)
        assertTrue(db.engineRecordDao().activeByRecordType(typeId).isEmpty())
    }

    @Test
    fun `an illegal choice value quarantines the row via checkChoiceLegality`() = runBlocking {
        val aspectId = aspect()
        val typeId = recordType(aspectId)
        val fieldId = choiceField(typeId, listOf("Active", "Sold"))
        val fieldDefs = db.fieldDefDao().forRecordType(typeId)

        val sheet = MirrorCodec.ParsedRecordSheet(typeId, "Item", listOf(row(null, null, mapOf(fieldId to "Scrapped"))))
        val outcome = sync.mergeRecordSheet(typeId, fieldDefs, sheet, MirrorStateStore.AspectSyncState())

        assertEquals(0, outcome.created)
        assertEquals(1, outcome.quarantined.size)
        assertTrue(outcome.quarantined.single().contains("Scrapped"))
    }

    @Test
    fun `a legal choice value creates normally`() = runBlocking {
        val aspectId = aspect()
        val typeId = recordType(aspectId)
        val fieldId = choiceField(typeId, listOf("Active", "Sold"))
        val fieldDefs = db.fieldDefDao().forRecordType(typeId)

        val sheet = MirrorCodec.ParsedRecordSheet(typeId, "Item", listOf(row(null, null, mapOf(fieldId to "Sold"))))
        val outcome = sync.mergeRecordSheet(typeId, fieldDefs, sheet, MirrorStateStore.AspectSyncState())

        assertEquals(1, outcome.created)
        assertTrue(outcome.quarantined.isEmpty())
    }

    // ---- remote delete: absent + older-than-export-stamp trashes ---------------------------------

    @Test
    fun `a local record absent from the file and older than the export stamp is trashed`() = runBlocking {
        val aspectId = aspect()
        val typeId = recordType(aspectId)
        val fieldId = textField(typeId)
        val fieldDefs = db.fieldDefDao().forRecordType(typeId)
        val id = (store.create(typeId, mapOf(fieldId to "gone"), RecordProvenance.USER) as RecordStore.WriteResult.Success).recordId
        val existing = db.engineRecordDao().getById(id)!!

        val sheet = MirrorCodec.ParsedRecordSheet(typeId, "Item", emptyList())
        val outcome = sync.mergeRecordSheet(
            typeId, fieldDefs, sheet,
            MirrorStateStore.AspectSyncState(lastExportAt = existing.updatedAt + 1_000),
        )

        assertEquals(1, outcome.trashed)
        val after = db.engineRecordDao().getById(id)!!
        assertNotNull(after.deletedAt)
    }

    @Test
    fun `a local record created after the export stamp is left alone even if absent from the file`() = runBlocking {
        val aspectId = aspect()
        val typeId = recordType(aspectId)
        val fieldId = textField(typeId)
        val fieldDefs = db.fieldDefDao().forRecordType(typeId)
        val id = (store.create(typeId, mapOf(fieldId to "brand-new"), RecordProvenance.USER) as RecordStore.WriteResult.Success).recordId
        val existing = db.engineRecordDao().getById(id)!!

        val sheet = MirrorCodec.ParsedRecordSheet(typeId, "Item", emptyList())
        val outcome = sync.mergeRecordSheet(
            typeId, fieldDefs, sheet,
            MirrorStateStore.AspectSyncState(lastExportAt = existing.updatedAt - 1_000),
        )

        assertEquals(0, outcome.trashed)
        val after = db.engineRecordDao().getById(id)!!
        assertNull(after.deletedAt)
    }

    // ---- definitions merge: unlocked fields update, locked fields never touched -------------------

    @Test
    fun `mergeDefinitions renames an unlocked field and leaves a locked field alone`() = runBlocking {
        val aspectId = aspect()
        val typeId = recordType(aspectId)
        val now = System.currentTimeMillis()
        val unlockedId = db.fieldDefDao().insert(FieldDef(recordTypeId = typeId, name = "Old Name", type = FieldType.TEXT, position = 0, createdAt = now, updatedAt = now))
        val lockedId = db.fieldDefDao().insert(
            FieldDef(recordTypeId = typeId, name = "Plugin Field", type = FieldType.TEXT, position = 1, ownerPluginId = "fleet", locked = true, createdAt = now, updatedAt = now),
        )

        val rows = listOf(
            MirrorCodec.ParsedDefinitionRow(
                aspectId = aspectId, aspectName = "Test", recordTypeId = typeId, recordTypeName = "Item",
                fieldDefId = unlockedId, fieldName = "New Name", fieldType = FieldType.TEXT,
                required = true, position = 0, config = null, locked = false, updatedAt = now,
            ),
            MirrorCodec.ParsedDefinitionRow(
                aspectId = aspectId, aspectName = "Test", recordTypeId = typeId, recordTypeName = "Item",
                fieldDefId = lockedId, fieldName = "Hand-Edited Plugin Name", fieldType = FieldType.TEXT,
                required = false, position = 1, config = null, locked = true, updatedAt = now,
            ),
        )

        val warnings = sync.mergeDefinitions(rows)

        assertTrue(warnings.isEmpty())
        val unlocked = db.fieldDefDao().getById(unlockedId)!!
        assertEquals("New Name", unlocked.name)
        assertTrue(unlocked.required)
        val locked = db.fieldDefDao().getById(lockedId)!!
        assertEquals("Plugin Field", locked.name) // untouched
    }

    @Test
    fun `mergeDefinitions warns on a row with no field id rather than acting on it`() = runBlocking {
        val warnings = sync.mergeDefinitions(
            listOf(
                MirrorCodec.ParsedDefinitionRow(
                    aspectId = null, aspectName = "New Aspect", recordTypeId = null, recordTypeName = "New Type",
                    fieldDefId = null, fieldName = "New Field", fieldType = FieldType.TEXT,
                    required = false, position = 0, config = null, locked = false, updatedAt = null,
                ),
            ),
        )
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("New Field"))
    }
}
