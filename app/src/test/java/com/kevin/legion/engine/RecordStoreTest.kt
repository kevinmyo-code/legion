package com.kevin.legion.engine

import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.DeletePolicy
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.RecordType
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
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
 * DB-backed coverage for [RecordStore] - the single write door ticket 16 built. Robolectric
 * through the real [CarDatabase.getDatabase] path, same shape as [com.kevin.legion.data.local.GoalDaoTest]
 * (see its doc comment for why a hand-rolled in-memory DB would not exercise the same wiring).
 *
 * Every test builds its own tiny schema by hand (an aspect, one or more record types, field defs)
 * rather than depending on any real aspect's shape - this ticket's brief is explicit that
 * fleet/ledger/pantry are NOT migrated onto the engine yet, so there is no real schema to borrow.
 */
@RunWith(RobolectricTestRunner::class)
class RecordStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)
    private val store get() = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private suspend fun aspect(name: String = "Fleet"): Long {
        val now = System.currentTimeMillis()
        return db.aspectDao().insert(Aspect(name = name, createdAt = now, updatedAt = now))
    }

    private suspend fun recordType(aspectId: Long, name: String): Long {
        val now = System.currentTimeMillis()
        return db.recordTypeDao().insert(RecordType(aspectId = aspectId, name = name, createdAt = now, updatedAt = now))
    }

    private suspend fun field(
        recordTypeId: Long,
        name: String,
        type: FieldType,
        config: String? = null,
    ): Long {
        val now = System.currentTimeMillis()
        return db.fieldDefDao().insert(
            FieldDef(recordTypeId = recordTypeId, name = name, type = type, config = config, createdAt = now, updatedAt = now),
        )
    }

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


    // ---- reference existence on write -----------------------------------------------------------

    @Test
    fun `create fails when a reference field points at a record that does not exist`() = runBlocking {
        val aspectId = aspect()
        val vehicleType = recordType(aspectId, "Vehicle")
        val serviceType = recordType(aspectId, "Service")
        val vehicleRefField = field(
            serviceType, "vehicle", FieldType.REFERENCE,
            FieldConfig.serializeReference(vehicleType, DeletePolicy.BLOCK),
        )

        val result = store.create(serviceType, mapOf(vehicleRefField to 999999L), RecordProvenance.USER)

        assertTrue(result is RecordStore.WriteResult.Failure)
        assertTrue((result as RecordStore.WriteResult.Failure).reason.contains("does not exist"))
    }

    @Test
    fun `create succeeds when a reference field points at a real live record`() = runBlocking {
        val aspectId = aspect()
        val vehicleType = recordType(aspectId, "Vehicle")
        val serviceType = recordType(aspectId, "Service")
        val vehicleRefField = field(
            serviceType, "vehicle", FieldType.REFERENCE,
            FieldConfig.serializeReference(vehicleType, DeletePolicy.BLOCK),
        )
        val vehicleId = (store.create(vehicleType, emptyMap(), RecordProvenance.USER) as RecordStore.WriteResult.Success).recordId

        val result = store.create(serviceType, mapOf(vehicleRefField to vehicleId), RecordProvenance.USER)

        assertTrue(result is RecordStore.WriteResult.Success)
    }

    @Test
    fun `create fails when a reference points at a trashed record`() = runBlocking {
        val aspectId = aspect()
        val vehicleType = recordType(aspectId, "Vehicle")
        val serviceType = recordType(aspectId, "Service")
        val vehicleRefField = field(
            serviceType, "vehicle", FieldType.REFERENCE,
            FieldConfig.serializeReference(vehicleType, DeletePolicy.NULLIFY),
        )
        val vehicleId = (store.create(vehicleType, emptyMap(), RecordProvenance.USER) as RecordStore.WriteResult.Success).recordId
        store.delete(vehicleId)

        val result = store.create(serviceType, mapOf(vehicleRefField to vehicleId), RecordProvenance.USER)

        assertTrue(result is RecordStore.WriteResult.Failure)
        assertTrue((result as RecordStore.WriteResult.Failure).reason.contains("trash"))
    }

    // ---- delete policy: BLOCK / CASCADE / NULLIFY -------------------------------------------------

    @Test
    fun `BLOCK policy refuses to delete a record still referenced, and changes nothing`() = runBlocking {
        val aspectId = aspect()
        val vehicleType = recordType(aspectId, "Vehicle")
        val serviceType = recordType(aspectId, "Service")
        val vehicleRefField = field(
            serviceType, "vehicle", FieldType.REFERENCE,
            FieldConfig.serializeReference(vehicleType, DeletePolicy.BLOCK),
        )
        val vehicleId = (store.create(vehicleType, emptyMap(), RecordProvenance.USER) as RecordStore.WriteResult.Success).recordId
        val serviceId = (
            store.create(serviceType, mapOf(vehicleRefField to vehicleId), RecordProvenance.USER)
                as RecordStore.WriteResult.Success
            ).recordId

        val result = store.delete(vehicleId)

        assertTrue(result is RecordStore.DeleteResult.Blocked)
        assertTrue((result as RecordStore.DeleteResult.Blocked).blockers.any { it.contains("#$serviceId") })
        assertNull("the vehicle must still be live", db.engineRecordDao().getById(vehicleId)!!.deletedAt)
    }

    @Test
    fun `CASCADE policy trashes the referencing child when the parent is deleted`() = runBlocking {
        val aspectId = aspect()
        val vehicleType = recordType(aspectId, "Vehicle")
        val serviceType = recordType(aspectId, "Service")
        val vehicleRefField = field(
            serviceType, "vehicle", FieldType.REFERENCE,
            FieldConfig.serializeReference(vehicleType, DeletePolicy.CASCADE),
        )
        val vehicleId = (store.create(vehicleType, emptyMap(), RecordProvenance.USER) as RecordStore.WriteResult.Success).recordId
        val serviceId = (
            store.create(serviceType, mapOf(vehicleRefField to vehicleId), RecordProvenance.USER)
                as RecordStore.WriteResult.Success
            ).recordId

        val result = store.delete(vehicleId)

        assertTrue(result is RecordStore.DeleteResult.Trashed)
        assertNotNull("the child must be trashed too", db.engineRecordDao().getById(serviceId)!!.deletedAt)
    }

    @Test
    fun `NULLIFY policy clears the reference on the child and leaves it live`() = runBlocking {
        val aspectId = aspect()
        val vehicleType = recordType(aspectId, "Vehicle")
        val serviceType = recordType(aspectId, "Service")
        val vehicleRefField = field(
            serviceType, "vehicle", FieldType.REFERENCE,
            FieldConfig.serializeReference(vehicleType, DeletePolicy.NULLIFY),
        )
        val vehicleId = (store.create(vehicleType, emptyMap(), RecordProvenance.USER) as RecordStore.WriteResult.Success).recordId
        val serviceId = (
            store.create(serviceType, mapOf(vehicleRefField to vehicleId), RecordProvenance.USER)
                as RecordStore.WriteResult.Success
            ).recordId

        store.delete(vehicleId)

        val child = db.engineRecordDao().getById(serviceId)!!
        assertNull("the child must survive, live", child.deletedAt)
        assertNull(
            "the reference field on the surviving child must be cleared",
            PayloadCodec.readReferenceId(JSONObject(child.payload), vehicleRefField),
        )
    }

    @Test
    fun `a CASCADE child that is itself BLOCKed by a third record refuses the ENTIRE delete, nothing trashed`() = runBlocking {
        // Vehicle --CASCADE--> Service --BLOCK--> Invoice. Deleting the vehicle would need to
        // cascade-trash the service, but a THIRD record (the invoice) blocks the service's own
        // delete - the whole tree must refuse, and the vehicle itself must survive untouched.
        // This is the exact shape senior review flagged: the old implementation discarded the
        // recursive DeleteResult and reported Trashed anyway, leaving the invoice pointing at a
        // trashed service.
        val aspectId = aspect()
        val vehicleType = recordType(aspectId, "Vehicle")
        val serviceType = recordType(aspectId, "Service")
        val invoiceType = recordType(aspectId, "Invoice")
        val vehicleRefField = field(
            serviceType, "vehicle", FieldType.REFERENCE,
            FieldConfig.serializeReference(vehicleType, DeletePolicy.CASCADE),
        )
        val serviceRefField = field(
            invoiceType, "service", FieldType.REFERENCE,
            FieldConfig.serializeReference(serviceType, DeletePolicy.BLOCK),
        )
        val vehicleId = (store.create(vehicleType, emptyMap(), RecordProvenance.USER) as RecordStore.WriteResult.Success).recordId
        val serviceId = (
            store.create(serviceType, mapOf(vehicleRefField to vehicleId), RecordProvenance.USER)
                as RecordStore.WriteResult.Success
            ).recordId
        val invoiceId = (
            store.create(invoiceType, mapOf(serviceRefField to serviceId), RecordProvenance.USER)
                as RecordStore.WriteResult.Success
            ).recordId

        val result = store.delete(vehicleId)

        assertTrue(result is RecordStore.DeleteResult.Blocked)
        assertTrue(
            "the blocker naming the deep BLOCK-guarded record must surface",
            (result as RecordStore.DeleteResult.Blocked).blockers.any { it.contains("#$invoiceId") },
        )
        assertNull("the top-level vehicle must NOT be trashed", db.engineRecordDao().getById(vehicleId)!!.deletedAt)
        assertNull("the cascade-candidate service must NOT be trashed either", db.engineRecordDao().getById(serviceId)!!.deletedAt)
    }

    @Test
    fun `a two-level clean CASCADE trashes parent, child, and grandchild together`() = runBlocking {
        // Vehicle --CASCADE--> Service --CASCADE--> Part. Nothing blocks anywhere in the tree, so
        // deleting the vehicle must trash all three.
        val aspectId = aspect()
        val vehicleType = recordType(aspectId, "Vehicle")
        val serviceType = recordType(aspectId, "Service")
        val partType = recordType(aspectId, "Part")
        val vehicleRefField = field(
            serviceType, "vehicle", FieldType.REFERENCE,
            FieldConfig.serializeReference(vehicleType, DeletePolicy.CASCADE),
        )
        val serviceRefField = field(
            partType, "service", FieldType.REFERENCE,
            FieldConfig.serializeReference(serviceType, DeletePolicy.CASCADE),
        )
        val vehicleId = (store.create(vehicleType, emptyMap(), RecordProvenance.USER) as RecordStore.WriteResult.Success).recordId
        val serviceId = (
            store.create(serviceType, mapOf(vehicleRefField to vehicleId), RecordProvenance.USER)
                as RecordStore.WriteResult.Success
            ).recordId
        val partId = (
            store.create(partType, mapOf(serviceRefField to serviceId), RecordProvenance.USER)
                as RecordStore.WriteResult.Success
            ).recordId

        val result = store.delete(vehicleId)

        assertTrue(result is RecordStore.DeleteResult.Trashed)
        assertNotNull("the vehicle must be trashed", db.engineRecordDao().getById(vehicleId)!!.deletedAt)
        assertNotNull("the service must be trashed", db.engineRecordDao().getById(serviceId)!!.deletedAt)
        assertNotNull("the grandchild part must be trashed too", db.engineRecordDao().getById(partId)!!.deletedAt)
    }

    // ---- trash / restore / purge -------------------------------------------------------------------

    @Test
    fun `a trashed record is not purged before the 30-day window`() = runBlocking {
        val aspectId = aspect()
        val noteType = recordType(aspectId, "Note")
        val recordId = (store.create(noteType, emptyMap(), RecordProvenance.USER) as RecordStore.WriteResult.Success).recordId
        val deletedAt = 1_000_000L
        store.delete(recordId, now = deletedAt)

        val purged = store.purgeExpiredTrash(now = deletedAt + TRASH_RETENTION_MS - 1)

        assertEquals(0, purged)
        assertNotNull("the row must still exist, just trashed", db.engineRecordDao().getById(recordId))
    }

    @Test
    fun `a trashed record is purged once the 30-day window has fully elapsed`() = runBlocking {
        val aspectId = aspect()
        val noteType = recordType(aspectId, "Note")
        val recordId = (store.create(noteType, emptyMap(), RecordProvenance.USER) as RecordStore.WriteResult.Success).recordId
        val deletedAt = 1_000_000L
        store.delete(recordId, now = deletedAt)

        val purged = store.purgeExpiredTrash(now = deletedAt + TRASH_RETENTION_MS + 1)

        assertEquals(1, purged)
        assertNull("the row must be gone entirely, not just flagged", db.engineRecordDao().getById(recordId))
    }

    @Test
    fun `restoring a trashed record clears the tombstone and keeps its data`() = runBlocking {
        val aspectId = aspect()
        val noteType = recordType(aspectId, "Note")
        val textField = field(noteType, "body", FieldType.TEXT)
        val recordId = (
            store.create(noteType, mapOf(textField to "buy milk"), RecordProvenance.USER)
                as RecordStore.WriteResult.Success
            ).recordId
        store.delete(recordId)

        val restored = store.restore(recordId)

        assertTrue(restored)
        val record = db.engineRecordDao().getById(recordId)!!
        assertNull(record.deletedAt)
        assertEquals("buy milk", PayloadCodec.readString(JSONObject(record.payload), textField))
    }

    // ---- computed fields: same-record arithmetic ---------------------------------------------------

    @Test
    fun `arithmetic computed field materializes on create from sibling fields`() = runBlocking {
        val aspectId = aspect()
        val invoiceType = recordType(aspectId, "Invoice")
        val subtotal = field(invoiceType, "subtotal", FieldType.MONEY_CENTS)
        val tax = field(invoiceType, "tax", FieldType.MONEY_CENTS)
        val total = field(
            invoiceType, "total", FieldType.COMPUTED,
            FieldConfig.serializeArithmetic(subtotal, ArithmeticOp.PLUS, tax),
        )

        val id = (
            store.create(invoiceType, mapOf(subtotal to 1000L, tax to 80L), RecordProvenance.USER)
                as RecordStore.WriteResult.Success
            ).recordId

        val record = db.engineRecordDao().getById(id)!!
        assertEquals(1080L, PayloadCodec.readLong(JSONObject(record.payload), total))
    }

    @Test
    fun `arithmetic computed field re-materializes on update`() = runBlocking {
        val aspectId = aspect()
        val invoiceType = recordType(aspectId, "Invoice")
        val subtotal = field(invoiceType, "subtotal", FieldType.MONEY_CENTS)
        val tax = field(invoiceType, "tax", FieldType.MONEY_CENTS)
        val total = field(
            invoiceType, "total", FieldType.COMPUTED,
            FieldConfig.serializeArithmetic(subtotal, ArithmeticOp.PLUS, tax),
        )
        val id = (
            store.create(invoiceType, mapOf(subtotal to 1000L, tax to 80L), RecordProvenance.USER)
                as RecordStore.WriteResult.Success
            ).recordId

        store.update(id, mapOf(subtotal to 2000L))

        val record = db.engineRecordDao().getById(id)!!
        assertEquals(2080L, PayloadCodec.readLong(JSONObject(record.payload), total))
    }

    // ---- computed fields: aggregation over referencing children, and invalidation --------------------

    @Test
    fun `aggregate SUM starts at a real zero with no children, and updates as children are added`() = runBlocking {
        val aspectId = aspect()
        val vehicleType = recordType(aspectId, "Vehicle")
        val serviceType = recordType(aspectId, "Service")
        val vehicleRefField = field(
            serviceType, "vehicle", FieldType.REFERENCE,
            FieldConfig.serializeReference(vehicleType, DeletePolicy.CASCADE),
        )
        val costField = field(serviceType, "cost", FieldType.MONEY_CENTS)
        val totalField = field(
            vehicleType, "totalServiceCost", FieldType.COMPUTED,
            FieldConfig.serializeAggregate(serviceType, vehicleRefField, AggregateOp.SUM, costField),
        )
        val vehicleId = (store.create(vehicleType, emptyMap(), RecordProvenance.USER) as RecordStore.WriteResult.Success).recordId

        val zeroChildren = db.engineRecordDao().getById(vehicleId)!!
        assertEquals(0L, PayloadCodec.readLong(JSONObject(zeroChildren.payload), totalField))

        store.create(serviceType, mapOf(vehicleRefField to vehicleId, costField to 1000L), RecordProvenance.USER)
        val afterOne = db.engineRecordDao().getById(vehicleId)!!
        assertEquals(1000L, PayloadCodec.readLong(JSONObject(afterOne.payload), totalField))

        store.create(serviceType, mapOf(vehicleRefField to vehicleId, costField to 500L), RecordProvenance.USER)
        val afterTwo = db.engineRecordDao().getById(vehicleId)!!
        assertEquals(1500L, PayloadCodec.readLong(JSONObject(afterTwo.payload), totalField))
    }

    @Test
    fun `aggregate SUM excludes a trashed child - deleting a child invalidates the parent`() = runBlocking {
        val aspectId = aspect()
        val vehicleType = recordType(aspectId, "Vehicle")
        val serviceType = recordType(aspectId, "Service")
        val vehicleRefField = field(
            serviceType, "vehicle", FieldType.REFERENCE,
            FieldConfig.serializeReference(vehicleType, DeletePolicy.NULLIFY),
        )
        val costField = field(serviceType, "cost", FieldType.MONEY_CENTS)
        val totalField = field(
            vehicleType, "totalServiceCost", FieldType.COMPUTED,
            FieldConfig.serializeAggregate(serviceType, vehicleRefField, AggregateOp.SUM, costField),
        )
        val vehicleId = (store.create(vehicleType, emptyMap(), RecordProvenance.USER) as RecordStore.WriteResult.Success).recordId
        val serviceId = (
            store.create(serviceType, mapOf(vehicleRefField to vehicleId, costField to 1000L), RecordProvenance.USER)
                as RecordStore.WriteResult.Success
            ).recordId
        store.create(serviceType, mapOf(vehicleRefField to vehicleId, costField to 500L), RecordProvenance.USER)

        store.delete(serviceId)

        val afterDelete = db.engineRecordDao().getById(vehicleId)!!
        assertEquals(500L, PayloadCodec.readLong(JSONObject(afterDelete.payload), totalField))
    }

    @Test
    fun `an aggregate mapped as the record type's primary amount field promotes into amountCents`() = runBlocking {
        val aspectId = aspect()
        val vehicleType = recordType(aspectId, "Vehicle")
        val serviceType = recordType(aspectId, "Service")
        val vehicleRefField = field(
            serviceType, "vehicle", FieldType.REFERENCE,
            FieldConfig.serializeReference(vehicleType, DeletePolicy.CASCADE),
        )
        val costField = field(serviceType, "cost", FieldType.MONEY_CENTS)
        val totalField = field(
            vehicleType, "totalServiceCost", FieldType.COMPUTED,
            FieldConfig.serializeAggregate(serviceType, vehicleRefField, AggregateOp.SUM, costField),
        )
        val vehicleTypeRow = db.recordTypeDao().getById(vehicleType)!!
        db.recordTypeDao().update(vehicleTypeRow.copy(primaryAmountFieldId = totalField))
        val vehicleId = (store.create(vehicleType, emptyMap(), RecordProvenance.USER) as RecordStore.WriteResult.Success).recordId

        store.create(serviceType, mapOf(vehicleRefField to vehicleId, costField to 4200L), RecordProvenance.USER)

        val vehicle = db.engineRecordDao().getById(vehicleId)!!
        assertEquals(4200L, vehicle.amountCents)
    }

    @Test
    fun `aggregate COUNT counts children regardless of a deleted source field`() = runBlocking {
        val aspectId = aspect()
        val vehicleType = recordType(aspectId, "Vehicle")
        val serviceType = recordType(aspectId, "Service")
        val vehicleRefField = field(
            serviceType, "vehicle", FieldType.REFERENCE,
            FieldConfig.serializeReference(vehicleType, DeletePolicy.CASCADE),
        )
        val countField = field(
            vehicleType, "serviceCount", FieldType.COMPUTED,
            FieldConfig.serializeAggregate(serviceType, vehicleRefField, AggregateOp.COUNT, sourceFieldId = null),
        )
        val vehicleId = (store.create(vehicleType, emptyMap(), RecordProvenance.USER) as RecordStore.WriteResult.Success).recordId

        store.create(serviceType, mapOf(vehicleRefField to vehicleId), RecordProvenance.USER)
        store.create(serviceType, mapOf(vehicleRefField to vehicleId), RecordProvenance.USER)

        val vehicle = db.engineRecordDao().getById(vehicleId)!!
        assertEquals(2, JSONObject(vehicle.payload).getInt(PayloadCodec.key(countField)))
    }
}
