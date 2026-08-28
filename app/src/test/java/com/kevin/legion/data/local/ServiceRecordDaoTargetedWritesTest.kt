package com.kevin.legion.data.local

import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Regression for ticket 11 §2's [ServiceRecordDao] additions
 * (`.scratch/fleet-maintenance/issues/11-service-history-cost-and-fleet-spend.md`) - the
 * `deleted = 0` filter every ordinary reader now applies, [ServiceRecordDao.editMileageAndCost]'s
 * targeted write, and [ServiceRecordDao.softDelete]'s tombstone (present, not absent, matching
 * [MaintenanceItemDaoTargetedWritesTest]'s own shape for the analogous `maintenance_items` column -
 * see that file's doc for why this pattern).
 *
 * Robolectric through the real [CarDatabase.getDatabase] path, same shape as
 * [MaintenanceItemDaoTargetedWritesTest].
 */
@RunWith(RobolectricTestRunner::class)
class ServiceRecordDaoTargetedWritesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val dao get() = CarDatabase.getDatabase(context).serviceRecordDao()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
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


    @Test
    fun `editMileageAndCost touches only mileage and costCents`() = runBlocking {
        dao.insert(ServiceRecord(vehicleId = "V1", serviceName = "Oil Change", mileage = 100_000, date = 1_000L, costCents = null))
        val id = dao.getRecentForVehicle("V1", 1).single().id

        val written = dao.editMileageAndCost(id, mileage = 100_050, costCents = 4599)

        assertEquals(1, written)
        val after = dao.getById(id)!!
        assertEquals(100_050, after.mileage)
        assertEquals(4599L, after.costCents)
        // serviceName/date/vehicleId/syncId ride along untouched.
        assertEquals("Oil Change", after.serviceName)
        assertEquals(1_000L, after.date)
        assertEquals("V1", after.vehicleId)
    }

    @Test
    fun `editMileageAndCost can clear an existing cost by passing null`() = runBlocking {
        dao.insert(ServiceRecord(vehicleId = "V1", serviceName = "Oil Change", mileage = 100_000, date = 1_000L, costCents = 4599))
        val id = dao.getRecentForVehicle("V1", 1).single().id

        dao.editMileageAndCost(id, mileage = 100_000, costCents = null)

        assertNull(dao.getById(id)!!.costCents)
    }

    @Test
    fun `editMileageAndCost against a nonexistent id affects zero rows`() = runBlocking {
        val written = dao.editMileageAndCost(999L, mileage = 1, costCents = null)
        assertEquals(0, written)
    }

    @Test
    fun `softDelete tombstones the row rather than removing it`() = runBlocking {
        dao.insert(ServiceRecord(vehicleId = "V1", serviceName = "Oil Change", mileage = 100_000, date = 1_000L))
        val id = dao.getRecentForVehicle("V1", 1).single().id

        val written = dao.softDelete(id)

        assertEquals(1, written)
        // Every ordinary reader excludes it...
        assertTrue(dao.getRecentForVehicle("V1", 10).isEmpty())
        assertTrue(dao.getRecordsForVehicle("V1").first().isEmpty())
        assertEquals(0, dao.countForVehicle("V1"))
        // ...but getById (the edit form's own loader) and a raw read still see it PRESENT, not
        // absent - the row must survive for sync's own raw-SQL snapshot to see, even though this
        // particular tombstone is LOCAL ONLY (ServiceRecord.deleted's own doc comment).
        val raw = dao.getById(id)
        assertTrue(raw != null)
        assertTrue(raw!!.deleted)
    }

    @Test
    fun `softDelete against an already-deleted id affects zero rows`() = runBlocking {
        dao.insert(ServiceRecord(vehicleId = "V1", serviceName = "Oil Change", mileage = 100_000, date = 1_000L))
        val id = dao.getRecentForVehicle("V1", 1).single().id
        dao.softDelete(id)

        val written = dao.softDelete(id)
        assertEquals("a re-delete must report zero, never silently succeed twice", 0, written)
    }

    @Test
    fun `deleted rows are excluded from totalCost, countWithCost, and hasRecordAtOrAfter`() = runBlocking {
        dao.insert(ServiceRecord(vehicleId = "V1", serviceName = "Oil Change", mileage = 100_000, date = 1_000L, costCents = 4599))
        val id = dao.getRecentForVehicle("V1", 1).single().id
        dao.softDelete(id)

        assertEquals("a tombstoned record's cost must not count toward spend", 0L, dao.totalCost("V1"))
        assertEquals(0, dao.countWithCost("V1"))
        assertEquals(0, dao.countForVehicle("V1"))
        assertTrue(
            "a deleted record must not block a legitimate backfill (ticket 11 §2)",
            !dao.hasRecordAtOrAfter("V1", "Oil Change", 0L),
        )
    }

    @Test
    fun `countWithCost counts only non-deleted records that actually carry a cost`() = runBlocking {
        dao.insert(ServiceRecord(vehicleId = "V1", serviceName = "Oil Change", mileage = 100_000, date = 1_000L, costCents = 4599))
        dao.insert(ServiceRecord(vehicleId = "V1", serviceName = "Tire Rotation", mileage = 101_000, date = 2_000L, costCents = null))

        assertEquals(1, dao.countWithCost("V1"))
        assertEquals(2, dao.countForVehicle("V1"))
    }

    // ================================================================================================
    // Engine retirement step 3 (ticket 16): `kind`/`updatedAt`, and the two new accessors
    // `insertReturningId`/`getBySyncId` that write path relies on.
    // ================================================================================================

    @Test
    fun `countInRange excludes an ASSERTED row even when its date falls inside the window`() = runBlocking {
        // A real logged service inside the window.
        dao.insert(ServiceRecord(vehicleId = "V1", serviceName = "Oil Change", mileage = 100_000, date = 5_000L, kind = "OBSERVED", updatedAt = 5_000L))
        // A driver-stated anchor whose date ALSO falls inside the window - MUST NOT count as a
        // service performed in the range (this object's own doc comment on countInRange: counting
        // it would invent a joint fact nobody stated, the reconciliation-gate rule 6 shape applied
        // to a recap statistic).
        dao.insert(ServiceRecord(vehicleId = "V1", serviceName = "Brake Pads", mileage = 90_000, date = 6_000L, kind = "ASSERTED", updatedAt = 6_000L))

        assertEquals("only the OBSERVED row counts", 1, dao.countInRange("V1", 0L, 10_000L))
    }

    @Test
    fun `insertReturningId hands back a usable row id`() = runBlocking {
        val id = dao.insertReturningId(ServiceRecord(vehicleId = "V1", serviceName = "Oil Change", mileage = 100_000, date = 1_000L))

        assertTrue("a real insert must return a positive rowid", id > 0)
        assertEquals("Oil Change", dao.getById(id)!!.serviceName)
    }

    @Test
    fun `getBySyncId finds a row regardless of its deleted flag`() = runBlocking {
        dao.insert(ServiceRecord(vehicleId = "V1", serviceName = "Oil Change", mileage = 100_000, date = 1_000L, syncId = "anchor-guid"))
        val id = dao.getBySyncId("anchor-guid")!!.id
        dao.softDelete(id)

        val found = dao.getBySyncId("anchor-guid")

        assertTrue("a tombstoned row must still be findable by its own syncId", found != null)
        assertTrue(found!!.deleted)
    }
}
