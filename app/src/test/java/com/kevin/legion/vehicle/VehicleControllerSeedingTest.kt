package com.kevin.legion.vehicle

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.testutil.RoomTestReset
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
 * Regression for `.scratch/fleet-maintenance/issues/13-the-jeep-row-lost-its-identity.md`:
 * [VehicleController.seedVehicle] must never persist a row, for any id, not just
 * the old sentinel special case. Before this fix, a read-path miss against ANY
 * unseen id (a fresh dongle, a stale synced id, a hand-typed one) silently
 * created a blank "this car" row - and [TelemetryRecorder.run] hits this path
 * every 30 seconds while driving, so a single transient miss on an id that
 * legitimately already had a real row could clobber it via [VehicleDao.upsert]'s
 * whole-row REPLACE.
 *
 * Robolectric through the real [CarDatabase.getDatabase] path, same shape as
 * [VehicleResolverTest] - see its doc for why a hand-rolled in-memory DB would
 * not exercise the same wiring.
 */
@RunWith(RobolectricTestRunner::class)
class VehicleControllerSeedingTest {
    private val context = RuntimeEnvironment.getApplication()
    private val dao get() = CarDatabase.getDatabase(context).vehicleDao()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        context.getSharedPreferences("active_vehicle", android.content.Context.MODE_PRIVATE).edit().clear().apply()
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
    fun `currentVehicle on a fresh install returns a placeholder that is never written to the roster`() = runBlocking {
        // No dongle connected, no car picked - ActiveVehicle.current falls all
        // the way back to DEFAULT_VEHICLE_ID, the shared sentinel this ticket's
        // predecessor bug (2026-08-12) already hardened against. This test is
        // the general case: EVERY id, not just that one.
        val placeholder = VehicleController.currentVehicle(context)

        assertEquals(VehicleController.DEFAULT_VEHICLE_ID, placeholder.obdMac)
        assertEquals("", placeholder.make)
        assertEquals("", placeholder.model)
        assertEquals(0, placeholder.year)
        // Ticket 04's label rule (`.scratch/fleet-maintenance/issues/04-one-car-label-rule.md`):
        // blank, not the retired "this car" sentinel - a magic string a caller has to remember to
        // filter is exactly what let the two archived rows carry it permanently.
        assertEquals("", placeholder.name)

        assertNull("seedVehicle must not have persisted a row", dao.getByMac(VehicleController.DEFAULT_VEHICLE_ID))
        assertTrue("getAll() must not contain the placeholder", dao.getAll().isEmpty())
        assertTrue("getAllIncludingArchived() must not contain it either", dao.getAllIncludingArchived().isEmpty())
    }

    @Test
    fun `a miss against an arbitrary never-seen id also seeds without persisting`() = runBlocking {
        val placeholder = VehicleController.vehicleFor(context, "totally:unseen:mac")

        assertEquals("totally:unseen:mac", placeholder.obdMac)
        assertNull(dao.getByMac("totally:unseen:mac"))
        assertTrue(dao.getAll().isEmpty())
    }

    @Test
    fun `repeated misses never accumulate roster rows`() = runBlocking {
        // The 2026-08-12 predecessor bug's symptom, generalized: TelemetryRecorder
        // calls a resolution path like this every 30 seconds. Calling it
        // repeatedly must never grow the roster.
        repeat(5) { VehicleController.currentVehicle(context) }

        assertTrue(dao.getAll().isEmpty())
        assertTrue(dao.getAllIncludingArchived().isEmpty())
    }

    @Test
    fun `an existing row is unaffected by a later miss on a different id`() = runBlocking {
        // The actual shape of the ticket's data-loss risk: one real car on
        // file, and a miss against some OTHER id (a different dongle, a stale
        // sync id) must not touch it.
        val real = com.kevin.legion.data.local.Vehicle(
            obdMac = "REAL:MAC",
            name = "1998 Jeep Cherokee",
            make = "Jeep",
            model = "Cherokee",
            year = 1998,
            personaPrompt = "",
            odometerBaseline = 118_374,
            confirmed = true,
            onboarded = true,
        )
        dao.upsert(real)

        VehicleController.vehicleFor(context, "some:other:unseen:mac")

        val stillReal = dao.getByMac("REAL:MAC")!!
        assertEquals("Jeep", stillReal.make)
        assertEquals("Cherokee", stillReal.model)
        assertEquals(1998, stillReal.year)
        assertEquals(118_374, stillReal.odometerBaseline)
        assertEquals(true, stillReal.onboarded)
        assertEquals(1, dao.getAll().size)
    }
}
