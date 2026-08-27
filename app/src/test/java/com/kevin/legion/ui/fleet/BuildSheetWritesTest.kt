package com.kevin.legion.ui.fleet

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.BuildSheetController
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Ticket 07 (command-center, `.scratch/command-center/issues/07-build-sheet-screen.md`) - pins
 * that [writeLogBuildEntry] (the BUILD SHEET screen's own write) and [BuildSheetController]'s spend
 * reads (what `service/LiveToolbox.kt`'s `get_spend`/`getSpend` dispatch calls) are the SAME write
 * and the SAME computation, never a parallel sum. Robolectric through the real
 * [CarDatabase.getDatabase] path, same shape as [com.kevin.legion.vehicle.VehicleControllerServiceWritesTest].
 */
@RunWith(RobolectricTestRunner::class)
class BuildSheetWritesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() = runBlocking {
        RoomTestReset.resetCarDatabaseSingleton()
        db.vehicleDao().upsert(
            Vehicle(
                obdMac = "V1", name = "Cherokee", make = "Jeep", model = "Cherokee", year = 1998,
                personaPrompt = "", odometerBaseline = 227_000, confirmed = true,
            ),
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


    @Test
    fun `writeLogBuildEntry refuses a blank title without ever calling the controller`() = runBlocking {
        val outcome = writeLogBuildEntry(context, "V1", "   ", "mod", null, "", "")
        assertFalse(outcome.success)
        assertTrue(db.buildEntryDao().getForVehicle("V1").isEmpty())
    }

    @Test
    fun `writeLogBuildEntry converts cents to the dollar figure BuildSheetController stores`() = runBlocking {
        val outcome = writeLogBuildEntry(context, "V1", "BC Racing coilovers", "mod", 89_900L, "FCP Euro", "front and rear")
        assertTrue("A landed write must report success. Was: $outcome", outcome.success)

        val entries = db.buildEntryDao().getForVehicle("V1")
        assertEquals(1, entries.size)
        assertEquals(899.0, entries.single().cost)
    }

    /**
     * THE gate this ticket's verification names: a build sheet entry logged by hand must move the
     * SAME `get_spend`-shaped figure a voice-logged entry would. `BuildSheetController.spendByCategory`/
     * `totalSpend` are the exact functions `service/LiveToolbox.kt`'s `getSpend` dispatch calls (see
     * that function's own doc comment) - this test calls them directly rather than re-implementing
     * `getSpend`'s JSON shape, because the point is that the UI and the voice tool are calling the
     * identical function, not that two independent implementations happen to agree today.
     */
    @Test
    fun `a hand-logged build entry moves the same spend figure get_spend reads`() = runBlocking {
        writeLogBuildEntry(context, "V1", "Oil filter", "consumable", 1_299L, "", "")
        writeLogBuildEntry(context, "V1", "Cabin air filter", "consumable", 2_501L, "", "")
        writeLogBuildEntry(context, "V1", "New clutch", "repair", 60_000L, "", "")

        val byCategory = BuildSheetController.spendByCategory(context, "V1")
        val total = BuildSheetController.totalSpend(context, "V1")

        assertEquals(38.0, byCategory["consumable"]!!, 0.001)
        assertEquals(600.0, byCategory["repair"]!!, 0.001)
        assertEquals(638.0, total, 0.001)
    }

    @Test
    fun `a cost-less build entry is legal and stays out of the spend total`() = runBlocking {
        val outcome = writeLogBuildEntry(context, "V1", "Dash cam", "part", null, "", "")
        assertTrue(outcome.success)

        val byCategory = BuildSheetController.spendByCategory(context, "V1")
        // BuildSheetController.spendByCategory only emits a key for a category with a POSITIVE
        // sum (its own SUM(cost) COALESCE-to-zero query) - a cost-less entry contributes nothing,
        // so "part" never appears rather than appearing as a misleading $0.00.
        assertFalse(byCategory.containsKey("part"))
        assertEquals(0.0, BuildSheetController.totalSpend(context, "V1"), 0.001)
    }
}
