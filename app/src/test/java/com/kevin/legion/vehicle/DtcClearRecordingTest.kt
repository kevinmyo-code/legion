package com.kevin.legion.vehicle

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [DtcClearController.recordOutcome]'s persistence half (D6/D8,
 * `.scratch/hands-and-senses/issues/01-clear-dtc.md`) - called directly (see that function's own
 * doc comment for why it is `internal`) with hand-built [DtcClearController.ClearResult]s, so this
 * exercises the REAL Room writes without needing a real OBD transport. Same Robolectric shape as
 * [com.kevin.legion.data.local.AdvisorAdviceDaoTest]/[com.kevin.legion.data.local.GoalDaoTest].
 */
@RunWith(RobolectricTestRunner::class)
class DtcClearRecordingTest {
    private val context = RuntimeEnvironment.getApplication()
    private val vehicle = Vehicle(
        obdMac = "AA:BB:CC:DD:EE:FF",
        name = "Test Car",
        make = "Jeep",
        model = "Cherokee",
        year = 1998,
        personaPrompt = "",
    )

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private fun cleared() = DtcClearController.ClearResult(
        outcome = DtcClearController.ClearOutcome.CLEARED,
        codesBefore = listOf("P0420", "P0128"),
        codesAfter = emptyList(),
        freezeFrameJson = "",
        ackRaw = "44\r\r",
        message = "Cleared. Nothing stored now.",
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


    @Test
    fun `a CLEARED outcome writes exactly one code_clear_events row and nothing else`() = runBlocking {
        DtcClearController.recordOutcome(context, vehicle, cleared())

        val db = CarDatabase.getDatabase(context)
        val rows = db.codeClearEventDao().getAll(vehicle.obdMac)
        assertEquals(1, rows.size)
        assertEquals("CLEARED", rows[0].outcome)
        assertEquals(JSONArray(listOf("P0420", "P0128")).toString(), rows[0].codesBeforeJson)
        assertEquals("[]", rows[0].codesAfterJson)

        // D6: clearing codes is a diagnostic act, not work performed on the car - nothing here may
        // ever touch the maintenance log or service history.
        assertTrue(
            "recordOutcome must never write a service_records row (D6)",
            db.serviceRecordDao().countForVehicle(vehicle.obdMac) == 0,
        )
        assertTrue(
            "recordOutcome must never write a maintenance_items row (D6)",
            db.maintenanceItemDao().getForVehicle(vehicle.obdMac).isEmpty(),
        )
    }

    @Test
    fun `REFUSED never writes a code_clear_events row - nothing was ever sent`() = runBlocking {
        val refused = DtcClearController.ClearResult(
            outcome = DtcClearController.ClearOutcome.REFUSED,
            codesBefore = emptyList(),
            codesAfter = null,
            freezeFrameJson = "",
            ackRaw = "",
            message = "The car is not answering. I have not sent anything.",
        )
        DtcClearController.recordOutcome(context, vehicle, refused)

        val db = CarDatabase.getDatabase(context)
        assertTrue(db.codeClearEventDao().getAll(vehicle.obdMac).isEmpty())
    }

    @Test
    fun `NOTHING_TO_CLEAR never writes a code_clear_events row - nothing was ever sent`() = runBlocking {
        val nothing = DtcClearController.ClearResult(
            outcome = DtcClearController.ClearOutcome.NOTHING_TO_CLEAR,
            codesBefore = emptyList(),
            codesAfter = null,
            freezeFrameJson = "",
            ackRaw = "",
            message = "Nothing stored. Nothing to clear.",
        )
        DtcClearController.recordOutcome(context, vehicle, nothing)

        val db = CarDatabase.getDatabase(context)
        assertTrue(db.codeClearEventDao().getAll(vehicle.obdMac).isEmpty())
    }

    @Test
    fun `the confirm-prompt turn (outcome null) logs nothing at all`() = runBlocking {
        val asking = DtcClearController.ClearResult(
            outcome = null,
            codesBefore = listOf("P0420"),
            codesAfter = null,
            freezeFrameJson = "",
            ackRaw = "",
            message = "One stored: P0420. ... Do you want me to clear?",
        )
        DtcClearController.recordOutcome(context, vehicle, asking)

        val db = CarDatabase.getDatabase(context)
        assertTrue(db.codeClearEventDao().getAll(vehicle.obdMac).isEmpty())
    }

    @Test
    fun `getLatestCleared only ever returns the CLEARED row, never RETURNED or UNVERIFIED`() = runBlocking {
        val returned = DtcClearController.ClearResult(
            outcome = DtcClearController.ClearOutcome.RETURNED,
            codesBefore = listOf("P0420"),
            codesAfter = listOf("P0420"),
            freezeFrameJson = "",
            ackRaw = "44\r\r",
            message = "Sent the clear. P0420 came straight back. That fault is active, not stored.",
        )
        DtcClearController.recordOutcome(context, vehicle, returned)
        DtcClearController.recordOutcome(context, vehicle, cleared())

        val db = CarDatabase.getDatabase(context)
        val latestCleared = db.codeClearEventDao().getLatestCleared(vehicle.obdMac)
        assertEquals("CLEARED", latestCleared?.outcome)
        assertEquals(2, db.codeClearEventDao().getAll(vehicle.obdMac).size)
    }
}
