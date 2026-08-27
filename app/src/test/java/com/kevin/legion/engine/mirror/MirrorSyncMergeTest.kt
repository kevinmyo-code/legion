package com.kevin.legion.engine.mirror

import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.RecordType
import com.kevin.legion.engine.FieldConfig
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Merge-semantics coverage for [MirrorSync] (ticket 20 item 6, updated by senior review MUST-FIX 1:
 * "merge semantics (latest-updatedAt wins, local-newer survives, remote-delete trashes, foreign-guid
 * creates...) all through a fake store"). "Fake store" here means exactly what [RecordStoreTest]
 * already establishes for the engine itself: a real, Robolectric-backed [CarDatabase]/[RecordStore],
 * with hand-built [MirrorCodec.ParsedRecordRow] values standing in for what [MirrorCodec] would have
 * parsed out of a real xlsx - there is no SAF, no [MirrorStore], no real Drive folder anywhere in
 * this file. [mergeRecordSheet] itself is marked `internal` for exactly this reason: it is the one
 * piece of [MirrorSync] worth exercising directly, without going through [MirrorSync.exportNow]'s
 * SAF-dependent orchestration.
 *
 * **Keyed by guid, never by local id** (senior review MUST-FIX 1) - every row this file builds
 * carries a [MirrorCodec.ParsedRecordRow.guid], and every lookup [mergeRecordSheet] does is by guid
 * ([com.kevin.legion.data.local.EngineRecordDao.getByGuid]), matching the fix this file's own tests
 * exist to confirm.
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
        guid: String?,
        updatedAt: Long?,
        fieldValues: Map<Long, Any?>,
        parseErrors: Map<Long, String> = emptyMap(),
        provenance: RecordProvenance? = null,
    ) = MirrorCodec.ParsedRecordRow(
        rowNumber = 1,
        guid = guid,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        provenance = provenance,
        provenanceReadOnly = false,
        fieldValues = fieldValues,
        fieldParseErrors = parseErrors,
        unmappedHeaders = emptyList(),
    )

    @After
    fun drainRoomInvalidationTracker() {
        // A DAO write anywhere in this test can schedule a Room InvalidationTracker refresh
        // on ArchTaskExecutor's disk-IO pool. If that refresh is still queued or running when
        // this test method returns, it races Robolectric's own per-@Test-METHOD native SQLite
        // reset and throws "Illegal connection pointer" on a background thread - uncaught, and
        // blamed by kotlinx-coroutines-test on whatever runTest starts next, not on this class.
        // Draining here, before Robolectric ever gets a chance to reset, is the fix - see
        // RoomTestReset's own class doc comment
        // (.scratch/hardening/issues/13-the-suite-is-green-by-luck.md) for the full account.
        RoomTestReset.drainArchDiskIoPool()
    }


    // ---- create: blank guid ----------------------------------------------------------------------

    @Test
    fun `a row with no guid and real content is created with a freshly minted guid`() = runBlocking {
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
        assertTrue("a freshly created record must carry a real, non-blank guid", stored.single().guid.isNotBlank())
    }

    // ---- create: foreign guid, no local match (the exact case that was broken) -------------------

    @Test
    fun `a foreign guid with no local match is created locally, preserving the exact guid`() = runBlocking {
        val aspectId = aspect()
        val typeId = recordType(aspectId)
        val fieldId = textField(typeId)
        val fieldDefs = db.fieldDefDao().forRecordType(typeId)

        // This guid was never created on THIS device - it stands in for a row exported by a
        // different phone and imported here for the first time. The old, broken behaviour (keyed
        // by local id) would have quarantined this as "record #<n> no longer exists locally" -
        // wrong, since it never existed here under any number. The fix: a real CREATE.
        val foreignGuid = "device-a-abc-123"
        val sheet = MirrorCodec.ParsedRecordSheet(
            typeId, "Item", listOf(row(foreignGuid, 5_000L, mapOf(fieldId to "from device A"))),
        )
        val outcome = sync.mergeRecordSheet(typeId, fieldDefs, sheet, MirrorStateStore.AspectSyncState())

        assertEquals(1, outcome.created)
        assertEquals(0, outcome.updated)
        assertTrue("a foreign-guid create must never be quarantined", outcome.quarantined.isEmpty())

        val stored = db.engineRecordDao().activeByRecordType(typeId).single()
        assertEquals("the local copy must carry the SAME guid the foreign row stated, never a fresh one", foreignGuid, stored.guid)
        assertEquals("from device A", PayloadCodec.readString(JSONObject(stored.payload), fieldId))
    }

    @Test
    fun `a foreign guid row's own provenance column is preserved on create when present`() = runBlocking {
        val aspectId = aspect()
        val typeId = recordType(aspectId)
        val fieldId = textField(typeId)
        val fieldDefs = db.fieldDefDao().forRecordType(typeId)

        val sheet = MirrorCodec.ParsedRecordSheet(
            typeId, "Item",
            listOf(row("foreign-reconciled-guid", 5_000L, mapOf(fieldId to "x"), provenance = RecordProvenance.DETERMINISTIC)),
        )
        sync.mergeRecordSheet(typeId, fieldDefs, sheet, MirrorStateStore.AspectSyncState())

        val stored = db.engineRecordDao().activeByRecordType(typeId).single()
        assertEquals(RecordProvenance.DETERMINISTIC, stored.provenance)
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

        val sheet = MirrorCodec.ParsedRecordSheet(typeId, "Item", listOf(row(existing.guid, existing.updatedAt + 10_000, mapOf(fieldId to "new"))))
        val outcome = sync.mergeRecordSheet(typeId, fieldDefs, sheet, MirrorStateStore.AspectSyncState())

        assertEquals(1, outcome.updated)
        assertEquals(0, outcome.created)
        assertTrue(outcome.quarantined.isEmpty())
        val updated = db.engineRecordDao().getById(id)!!
        assertEquals("new", PayloadCodec.readString(JSONObject(updated.payload), fieldId))
    }

    // ---- both sides changed since the last export: the file's newer stated timestamp still wins ---

    @Test
    fun `both sides changed since the export stamp - the file's newer stated updatedAt wins outright`() = runBlocking {
        // This is deliberately DIFFERENT from "a local record edited after the last export survives
        // a stale file-side edit" below: there, the file row's updatedAt was NOT advanced (a plain
        // Sheets hand edit). Here, the file row's updatedAt WAS legitimately advanced past the
        // local record's own - the shape a SECOND DEVICE'S in-app edit produces, since that device's
        // own RecordStore.update call really did bump its row's updatedAt before it was exported.
        // mergeRecordSheet's own doc comment names this exact scenario as the case fileNewer must
        // win regardless of localMovedSinceExport - this test asserts that branch directly.
        val aspectId = aspect()
        val typeId = recordType(aspectId)
        val fieldId = textField(typeId)
        val fieldDefs = db.fieldDefDao().forRecordType(typeId)
        val id = (store.create(typeId, mapOf(fieldId to "original"), RecordProvenance.USER) as RecordStore.WriteResult.Success).recordId
        val afterCreate = db.engineRecordDao().getById(id)!!
        val exportStamp = afterCreate.updatedAt - 1 // export happened just before this record existed

        // LOCAL side changes after the export stamp:
        store.update(id, mapOf(fieldId to "local-change"), afterCreate.updatedAt + 1_000)
        val afterLocalEdit = db.engineRecordDao().getById(id)!!

        // FILE side ALSO changed since the export stamp, and with a genuinely newer updatedAt than
        // local's current value - the other device's own edit, legitimately timestamped.
        val sheet = MirrorCodec.ParsedRecordSheet(
            typeId, "Item",
            listOf(row(afterLocalEdit.guid, afterLocalEdit.updatedAt + 5_000, mapOf(fieldId to "remote-change"))),
        )
        val outcome = sync.mergeRecordSheet(
            typeId, fieldDefs, sheet,
            MirrorStateStore.AspectSyncState(lastExportAt = exportStamp),
        )

        assertEquals("the file's stated updatedAt was newer, so it must win even though local ALSO changed since export", 1, outcome.updated)
        assertEquals(0, outcome.unchanged)
        val after = db.engineRecordDao().getById(id)!!
        assertEquals("remote-change", PayloadCodec.readString(JSONObject(after.payload), fieldId))
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

        val sheet = MirrorCodec.ParsedRecordSheet(typeId, "Item", listOf(row(existing.guid, existing.updatedAt - 5_000, mapOf(fieldId to "same"))))
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
        val sheet = MirrorCodec.ParsedRecordSheet(typeId, "Item", listOf(row(afterCreate.guid, afterCreate.updatedAt, mapOf(fieldId to "exported-value"))))
        val outcome = sync.mergeRecordSheet(
            typeId, fieldDefs, sheet,
            MirrorStateStore.AspectSyncState(lastExportAt = exportStamp),
        )

        assertEquals(0, outcome.updated)
        assertEquals(1, outcome.unchanged) // local wins, file content left unapplied
        val after = db.engineRecordDao().getById(id)!!
        assertEquals("local-edit-after-export", PayloadCodec.readString(JSONObject(after.payload), fieldId))
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

        val sheet = MirrorCodec.ParsedRecordSheet(typeId, "Item", listOf(row(existing.guid, existing.updatedAt + 10_000, mapOf(fieldId to "hand-edited-value"))))
        val outcome = sync.mergeRecordSheet(typeId, fieldDefs, sheet, MirrorStateStore.AspectSyncState())

        assertEquals(0, outcome.updated)
        assertEquals(1, outcome.quarantined.size)
        assertTrue(outcome.quarantined.single().contains("read-only"))
        val after = db.engineRecordDao().getById(id)!!
        assertEquals("reconciled-value", PayloadCodec.readString(JSONObject(after.payload), fieldId))
    }

    @Test
    fun `a DETERMINISTIC row with UNCHANGED content is a silent no-op, not a quarantine`() = runBlocking {
        val aspectId = aspect()
        val typeId = recordType(aspectId)
        val fieldId = textField(typeId)
        val fieldDefs = db.fieldDefDao().forRecordType(typeId)
        val id = (store.create(typeId, mapOf(fieldId to "value"), RecordProvenance.DETERMINISTIC) as RecordStore.WriteResult.Success).recordId
        val existing = db.engineRecordDao().getById(id)!!

        val sheet = MirrorCodec.ParsedRecordSheet(typeId, "Item", listOf(row(existing.guid, existing.updatedAt, mapOf(fieldId to "value"))))
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

    // ---- round trip: created-on-A, imported to B, re-exported from B keeps the same guid ---------

    @Test
    fun `a record created on device A, imported to device B, re-exports from B with the SAME guid`() = runBlocking {
        // "Device A": build a record purely as data (never written to THIS test's Room instance -
        // there is only one CarDatabase in a Robolectric test, so "device A" is simulated by never
        // calling RecordStore here, only by hand-building the EngineRecord/export shape exactly as
        // MirrorSync.buildExport would have).
        val aspectA = Aspect(id = 1, name = "Test", createdAt = 1000, updatedAt = 1000)
        val recordTypeIdOnA = 10L
        val fieldIdOnA = 100L
        val recordTypeA = RecordType(id = recordTypeIdOnA, aspectId = 1, name = "Item", createdAt = 1000, updatedAt = 1000)
        val fieldDefA = FieldDef(id = fieldIdOnA, recordTypeId = recordTypeIdOnA, name = "Note", type = FieldType.TEXT, position = 0, createdAt = 1000, updatedAt = 1000)
        val guidFromA = "device-a-" + java.util.UUID.randomUUID().toString()
        val recordFromA = com.kevin.legion.data.local.EngineRecord(
            id = 777, // device A's own local id - must NEVER survive onto device B
            recordTypeId = recordTypeIdOnA, createdAt = 1000, updatedAt = 2000,
            provenance = RecordProvenance.USER, payload = JSONObject().put(fieldIdOnA.toString(), "born on A").toString(),
            guid = guidFromA,
        )
        val exportFromA = MirrorCodec.MirrorAspectExport(
            aspectA, listOf(MirrorCodec.MirrorRecordTypeExport(recordTypeA, listOf(fieldDefA), listOf(recordFromA))),
        )
        val bytesFromA = MirrorCodec.recordsToWorkbookBytes(exportFromA)

        // "Device B": THIS test's real Room instance, with its OWN local schema (different local
        // aspect/record-type/field ids on purpose - a real second phone would have its own
        // AUTOINCREMENT sequence too). Parse A's bytes using device B's schema id mapping by feeding
        // the parsed row straight into mergeRecordSheet, matching how MirrorSync.importAspectFile
        // pairs a parsed sheet with the LOCAL fieldDefs of the matching record type.
        val parsedFromA = MirrorCodec.workbookBytesToParsedWorkbook(bytesFromA)
        val parsedRow = parsedFromA.recordSheets.single().rows.single()
        assertEquals(guidFromA, parsedRow.guid)

        val aspectIdOnB = aspect()
        val typeIdOnB = recordType(aspectIdOnB, "Item")
        val fieldIdOnB = textField(typeIdOnB, "Note") // a DIFFERENT local id than fieldIdOnA=100, by construction
        assertNotEquals(fieldIdOnA, fieldIdOnB)
        val fieldDefsOnB = db.fieldDefDao().forRecordType(typeIdOnB)

        // Re-key the parsed row's fieldValues from A's field id to B's - MirrorSync itself does this
        // implicitly because the _definitions sheet it reads back is keyed by NAME-matched fields
        // recovered from the SAME workbook; here it is done explicitly since B's schema was built
        // independently rather than round-tripped through a shared definitions sheet.
        val rowOnB = parsedRow.copy(fieldValues = mapOf(fieldIdOnB to parsedRow.fieldValues.getValue(fieldIdOnA)))
        val sheetOnB = MirrorCodec.ParsedRecordSheet(typeIdOnB, "Item", listOf(rowOnB))

        val outcome = sync.mergeRecordSheet(typeIdOnB, fieldDefsOnB, sheetOnB, MirrorStateStore.AspectSyncState())
        assertEquals(1, outcome.created)
        assertTrue(outcome.quarantined.isEmpty())

        val storedOnB = db.engineRecordDao().activeByRecordType(typeIdOnB).single()
        assertEquals("the SAME guid device A assigned, never a fresh one", guidFromA, storedOnB.guid)
        assertNotEquals("B's local id must be its own, never A's 777", 777L, storedOnB.id)

        // Now re-export FROM B and confirm the guid survives the round trip unchanged.
        val exportFromB = MirrorCodec.MirrorAspectExport(
            Aspect(id = aspectIdOnB, name = "Test", createdAt = 1000, updatedAt = 1000),
            listOf(MirrorCodec.MirrorRecordTypeExport(db.recordTypeDao().getById(typeIdOnB)!!, fieldDefsOnB, listOf(storedOnB))),
        )
        val reExportedFromB = MirrorCodec.workbookBytesToParsedWorkbook(MirrorCodec.recordsToWorkbookBytes(exportFromB))
        val reExportedRow = reExportedFromB.recordSheets.single().rows.single()
        assertEquals("re-exporting from B must carry the identical guid, not a new one", guidFromA, reExportedRow.guid)
    }
}
