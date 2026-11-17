package com.kevin.legion.vehicle.diag

import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.FleetEngineStore
import com.kevin.legion.vehicle.diag.sim.SimCars
import com.kevin.legion.vehicle.diag.sim.SimElm327
import com.kevin.legion.vehicle.diag.sim.SimTransport
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
 * Scans are stored per car (the dongle's car from the VIN), held under a pending id while the car
 * is unknown, and move with the rest of the OBD data when Kevin answers. Made-up ids and VINs.
 */
@RunWith(RobolectricTestRunner::class)
class DiagScanStoreTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() = RoomTestReset.resetCarDatabaseSingleton()

    @After
    fun tearDown() = RoomTestReset.drainArchDiskIoPool()

    private fun simScan(): ScanReport {
        val elm = SimElm327(SimCars.canTruck())
        val t = SimTransport(elm).also { it.handshake() }
        return runBlocking {
            ModuleScanner(t.link, clock = { elm.nowMs }, pause = { elm.nowMs += it })
                .scan(ModuleScanner.Options(make = "Ford"))
        }
    }

    @Test
    fun `a scan is saved with its raw replies and read back newest first`() = runBlocking {
        val report = simScan()
        val first = DiagScanStore.save(context, "car:truck", report)
        val second = DiagScanStore.save(context, "car:truck", report.copy(startedAtMs = report.startedAtMs + 1))
        val back = DiagScanStore.latest(context, "car:truck", 2)
        assertEquals(listOf(second, first), back.map { it.row.id })
        assertEquals(report.modules.size, back[1].row.moduleCount)
        assertTrue(back[1].report.modules.first { it.address == "760" }.codes.raw.contains("768 10"))
        assertTrue(DiagScanStore.latest(context, "car:other").isEmpty())
    }

    @Test
    fun `a held scan moves to the car Kevin names`() = runBlocking {
        val held = "pending:vin:${SimCars.FAKE_VIN}"
        DiagScanStore.save(context, held, simScan())
        assertEquals("Ford", DiagScanStore.makeFor(context, held))
        FleetEngineStore.reattributeObdData(
            context,
            FleetEngineStore.ObdDataMove(held, "car:truck", 0, Long.MAX_VALUE, windowed = false),
        )
        assertTrue(DiagScanStore.latest(context, held).isEmpty())
        assertEquals(1, DiagScanStore.latest(context, "car:truck").size)
    }

    @Test
    fun `maker codes`() {
        assertEquals("Ford", Wmi.make("1FTEW1EG0HFA00042"))
        assertEquals("Mitsubishi", Wmi.make("JA4AD3A30LZ000001"))
        assertEquals("Jeep", Wmi.make("1J4FJ68S0WL000001"))
        assertNull(Wmi.make("ZZZ00000000000000"))
    }

    @Test
    fun `voice clear picks only planned targets`() {
        val plan = ClearPlan(
            listOf(
                ClearTarget("760", "ABS (brakes) (760)", ClearPlanner.SERVICE_UDS),
                ClearTarget("engine", "engine", "x"),
            ),
            listOf(ClearExcluded("RCM (airbag / restraints) (737)", ClearPlanner.RESTRAINT_RULE)),
        )
        assertEquals(2, DiagVoice.pick(plan, "all").size)
        assertEquals(listOf("760"), DiagVoice.pick(plan, "abs").map { it.address })
        assertTrue("an excluded module is never picked", DiagVoice.pick(plan, "airbag").isEmpty())
    }
}
