package com.kevin.legion.data.local

import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Regression for `.scratch/fleet-maintenance/issues/05-an-edit-that-actually-sticks.md`'s
 * targeted-column fix on [MaintenanceItemDao], mirroring [VehicleDaoTargetedWritesTest]'s own
 * shape (see its doc for why this pattern - seed every column with a DISTINCT recognisable value,
 * call exactly one targeted writer, assert every untouched column survives byte-for-byte).
 *
 * Also pins the ticket 07 tombstone contract: [MaintenanceItemDao.getForVehicle]/[MaintenanceItemDao.get]
 * filter `deleted = 0`, and [MaintenanceItemDao.softDelete] leaves the row present (not absent) so
 * it can still ship to Drive and propagate - see [MaintenanceItemDao.softDelete]'s own doc.
 *
 * Robolectric through the real [CarDatabase.getDatabase] path, same shape as [VehicleDaoTargetedWritesTest].
 */
@RunWith(RobolectricTestRunner::class)
class MaintenanceItemDaoTargetedWritesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val dao get() = CarDatabase.getDatabase(context).maintenanceItemDao()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    /** Every column set to a value distinct from every other's default, so cross-contamination is loud. */
    private fun fullItem(vehicleId: String, serviceName: String) = MaintenanceItem(
        vehicleId = vehicleId,
        serviceName = serviceName,
        intervalMiles = 5_000,
        intervalMonths = 6,
        lastDoneMileage = 100_000,
        lastDoneDate = 1_000L,
        updatedAt = 2_000L,
        neverDone = false,
        intervalSource = "SEEDED",
        deleted = false,
    )

    @Test
    fun `setIntervals touches only the interval columns and updatedAt`() = runBlocking {
        dao.upsertStamped(fullItem("V1", "Oil Change"))
        val before = dao.get("V1", "Oil Change")!!

        val written = dao.setIntervals("V1", "Oil Change", miles = 7_500, months = 12, source = "CONFIRMED", now = 9_999L)

        val after = dao.get("V1", "Oil Change")!!
        assertEquals(1, written)
        assertEquals(7_500, after.intervalMiles)
        assertEquals(12, after.intervalMonths)
        assertEquals("CONFIRMED", after.intervalSource)
        assertEquals(9_999L, after.updatedAt)
        // Anchor and tombstone columns ride along untouched.
        assertEquals(before.copy(intervalMiles = 7_500, intervalMonths = 12, intervalSource = "CONFIRMED", updatedAt = 9_999L), after)
    }

    @Test
    fun `setIntervals against a nonexistent pair affects zero rows`() = runBlocking {
        val written = dao.setIntervals("V1", "Nonexistent", miles = 5_000, months = null, source = "CONFIRMED", now = 1L)
        assertEquals(0, written)
        assertNull(dao.get("V1", "Nonexistent"))
    }

    @Test
    fun `setAnchor touches only lastDoneMileage, lastDoneDate, neverDone and updatedAt`() = runBlocking {
        dao.upsertStamped(fullItem("V1", "Oil Change").copy(neverDone = true, lastDoneMileage = null, lastDoneDate = null))
        val before = dao.get("V1", "Oil Change")!!

        val written = dao.setAnchor("V1", "Oil Change", mileage = 150_000, date = 8_888L, now = 7_777L)

        val after = dao.get("V1", "Oil Change")!!
        assertEquals(1, written)
        assertEquals(150_000, after.lastDoneMileage)
        assertEquals(8_888L, after.lastDoneDate)
        // A real anchor un-confirms neverDone - see MaintenanceItemDao.setAnchor's doc.
        assertEquals(false, after.neverDone)
        assertEquals(7_777L, after.updatedAt)
        // Interval columns ride along untouched.
        assertEquals(
            before.copy(lastDoneMileage = 150_000, lastDoneDate = 8_888L, neverDone = false, updatedAt = 7_777L),
            after,
        )
    }

    @Test
    fun `setAnchor can clear one axis while setting the other`() = runBlocking {
        dao.upsertStamped(fullItem("V1", "Oil Change"))

        // Mileage-only backfill: date is passed null, deliberately clearing a stale prior date.
        dao.setAnchor("V1", "Oil Change", mileage = 175_000, date = null, now = 3_000L)

        val after = dao.get("V1", "Oil Change")!!
        assertEquals(175_000, after.lastDoneMileage)
        assertNull(after.lastDoneDate)
    }

    @Test
    fun `setNeverDone touches only neverDone, clears both anchors, and touches updatedAt`() = runBlocking {
        dao.upsertStamped(fullItem("V1", "Tire Rotation"))
        val before = dao.get("V1", "Tire Rotation")!!

        val written = dao.setNeverDone("V1", "Tire Rotation", now = 6_543L)

        val after = dao.get("V1", "Tire Rotation")!!
        assertEquals(1, written)
        assertEquals(true, after.neverDone)
        assertNull("neverDone REPLACES any prior anchor, it does not coexist with one", after.lastDoneMileage)
        assertNull(after.lastDoneDate)
        assertEquals(6_543L, after.updatedAt)
        assertEquals(
            before.copy(neverDone = true, lastDoneMileage = null, lastDoneDate = null, updatedAt = 6_543L),
            after,
        )
    }

    @Test
    fun `softDelete tombstones the row rather than removing it`() = runBlocking {
        dao.upsertStamped(fullItem("V1", "Cabin Air Filter"))

        val written = dao.softDelete("V1", "Cabin Air Filter", now = 4_444L)

        assertEquals(1, written)
        // The ordinary reader sees nothing - deleted = 0 is the filter every
        // reader except sync's raw-SQL snapshot must apply.
        assertNull(dao.get("V1", "Cabin Air Filter"))
        assertTrue(dao.getForVehicle("V1").isEmpty())
        // But the row is PRESENT, not absent - a tombstone must ship to sync,
        // or it can never propagate a delete across devices (ticket 07).
        val raw = CarDatabase.getDatabase(context).openHelper.readableDatabase
            .query("SELECT deleted, updatedAt FROM maintenance_items WHERE vehicleId = 'V1' AND serviceName = 'Cabin Air Filter'")
        raw.use {
            assertTrue("Tombstoned row must still exist for sync to see", it.moveToFirst())
            assertEquals(1, it.getInt(0))
            assertEquals(4_444L, it.getLong(1))
        }
    }

    @Test
    fun `softDelete against a nonexistent pair affects zero rows`() = runBlocking {
        val written = dao.softDelete("V1", "Nonexistent", now = 1L)
        assertEquals(0, written)
    }

    @Test
    fun `getForVehicle excludes tombstoned items but includes everything else`() = runBlocking {
        dao.upsertStamped(fullItem("V1", "Oil Change"))
        dao.upsertStamped(fullItem("V1", "Brake Pads"))
        dao.softDelete("V1", "Brake Pads", now = 1L)

        val items = dao.getForVehicle("V1")

        assertEquals(1, items.size)
        assertEquals("Oil Change", items.single().serviceName)
    }

    @Test
    fun `insertAll IGNORE cannot resurrect a tombstoned item`() = runBlocking {
        dao.upsertStamped(fullItem("V1", "Brake Pads"))
        dao.softDelete("V1", "Brake Pads", now = 1L)

        // A re-seed offering the same (vehicleId, serviceName) must not bring
        // the deleted row back - IGNORE leaves the existing (tombstoned) row
        // alone, which is the deliberate mechanism ticket 07 names.
        dao.insertAll(listOf(fullItem("V1", "Brake Pads").copy(deleted = false)))

        assertNull("Re-seeding must not resurrect a deleted item", dao.get("V1", "Brake Pads"))
    }

    // ------------------------------------------------------------- ticket 14

    @Test
    fun `getForVehicleIncludingDeleted returns both active and tombstoned rows`() = runBlocking {
        dao.upsertStamped(fullItem("V1", "Oil Change"))
        dao.upsertStamped(fullItem("V1", "Brake Pads"))
        dao.softDelete("V1", "Brake Pads", now = 1L)

        val all = dao.getForVehicleIncludingDeleted("V1")

        assertEquals(2, all.size)
        assertEquals(setOf("Oil Change", "Brake Pads"), all.map { it.serviceName }.toSet())
        assertTrue("The tombstoned row must be included, deleted = true", all.first { it.serviceName == "Brake Pads" }.deleted)
    }

    @Test
    fun `restore un-tombstones and writes the interval in one call`() = runBlocking {
        dao.upsertStamped(fullItem("V1", "Tire Rotation").copy(intervalMiles = 3_000, intervalMonths = null, intervalSource = "SEEDED"))
        dao.softDelete("V1", "Tire Rotation", now = 1L)
        assertNull("Sanity: tombstoned before restore", dao.get("V1", "Tire Rotation"))

        val written = dao.restore("V1", "Tire Rotation", miles = 7_500, months = 6, source = "CONFIRMED", now = 5_555L)

        assertEquals(1, written)
        val after = dao.get("V1", "Tire Rotation")!!
        assertEquals(false, after.deleted)
        assertEquals(7_500, after.intervalMiles)
        assertEquals(6, after.intervalMonths)
        assertEquals("CONFIRMED", after.intervalSource)
        assertEquals(5_555L, after.updatedAt)
    }

    @Test
    fun `restore against a nonexistent pair affects zero rows`() = runBlocking {
        val written = dao.restore("V1", "Nonexistent", miles = 5_000, months = null, source = "CONFIRMED", now = 1L)
        assertEquals(0, written)
    }
}
