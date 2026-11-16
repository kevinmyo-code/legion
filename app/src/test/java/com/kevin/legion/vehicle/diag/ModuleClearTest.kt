package com.kevin.legion.vehicle.diag

import com.kevin.legion.vehicle.diag.sim.SimCar
import com.kevin.legion.vehicle.diag.sim.SimCars
import com.kevin.legion.vehicle.diag.sim.SimElm327
import com.kevin.legion.vehicle.diag.sim.SimModule
import com.kevin.legion.vehicle.diag.sim.SimTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which modules may be cleared (failing closed), and what a clear says it did. */
class ModuleClearTest {

    private class Rig(car: SimCar, make: String?) {
        val elm = SimElm327(car)
        private val t = SimTransport(elm).also { it.handshake() }
        val report: ScanReport = runBlocking {
            ModuleScanner(t.link, clock = { elm.nowMs }, pause = { elm.nowMs += it })
                .scan(ModuleScanner.Options(make = make))
        }

        fun clear(targets: List<ClearTarget>): ClearRun =
            runBlocking { ModuleClearer(t.link, report.bus).clear(targets, report) }
    }

    private fun SimCar.module(id: Long): SimModule = modules.first { it.requestId == id }

    @Test
    fun `plan offers emissions and table-named modules, never the airbag, never the unread`() {
        val plan = ClearPlanner.plan(Rig(SimCars.canTruck(), "Ford").report)
        assertEquals(listOf("720", "760", "7D0", "7E0", "engine"), plan.targets.map { it.address }.sorted())
        assertEquals(ClearPlanner.SERVICE_KWP, plan.targets.first { it.address == "720" }.service)
        val excluded = plan.excluded.associate { it.label to it.reason }
        assertEquals(ClearPlanner.RESTRAINT_RULE, excluded.entries.first { "737" in it.key }.value)
        assertEquals(ClearPlanner.UNREAD_RULE, excluded.entries.first { "726" in it.key }.value)
        assertEquals(ClearPlanner.UNREAD_RULE, excluded.entries.first { "733" in it.key }.value)
    }

    @Test
    fun `with no make table only the emissions addresses may be cleared`() {
        val plan = ClearPlanner.plan(Rig(SimCars.canTruck(), null).report)
        assertEquals(listOf("7E0", "engine"), plan.targets.map { it.address }.sorted())
        assertTrue(plan.excluded.any { "760" in it.label && it.reason == ClearPlanner.UNNAMED_RULE })
    }

    @Test
    fun `when the table's airbag address did not answer, the table is not trusted for clearing`() {
        val car = SimCars.canTruck()
        val noRcm = SimCar(car.bus, car.modules.filter { it.requestId != 0x737L })
        val plan = ClearPlanner.plan(Rig(noRcm, "Ford").report)
        assertEquals(listOf("7E0", "engine"), plan.targets.map { it.address }.sorted())
    }

    @Test
    fun `clear reports exactly which modules confirmed and never touches the airbag module`() {
        val car = SimCars.canTruck()
        val rig = Rig(car, "Ford")
        val run = rig.clear(ClearPlanner.plan(rig.report).targets)
        val by = run.results.associate { it.target.address to it.outcome }
        assertEquals(ClearOutcome.CONFIRMED, by["engine"])
        assertEquals(ClearOutcome.CONFIRMED, by["7E0"])
        assertEquals(ClearOutcome.CONFIRMED, by["760"])
        assertEquals("KWP module refused the clear", ClearOutcome.REFUSED, by["720"])
        assertTrue(car.module(0x737).received.none { it.first() == Uds.CLEAR_DTC })
        val words = ScanWords.clearSummary("truck", run)
        assertTrue(words, words.startsWith("Confirmed clear on the truck: "))
        assertTrue(words, "the module refused the clear" in words)
        assertTrue(words, "not that the fault is fixed" in words)
    }

    @Test
    fun `engine running or moving sends nothing`() {
        val car = SimCars.canTruck()
        val rig = Rig(car, "Ford")
        val targets = ClearPlanner.plan(rig.report).targets
        car.rpm = 750
        assertEquals(ModuleClearer.RUNNING, rig.clear(targets).refusedAll)
        car.rpm = 0
        car.speedKmh = 5
        assertEquals(ModuleClearer.MOVING, rig.clear(targets).refusedAll)
        assertTrue(car.modules.all { m -> m.received.none { it.first() == Uds.CLEAR_DTC || it.first() == 0x04 } })
    }

    @Test
    fun `pre-CAN car clears through generic mode 04 only`() {
        val car = SimCars.preCan(BusKind.J1850_VPW)
        val rig = Rig(car, "Jeep")
        val plan = ClearPlanner.plan(rig.report)
        assertEquals(listOf("engine"), plan.targets.map { it.address })
        val run = rig.clear(plan.targets)
        assertEquals(ClearOutcome.CONFIRMED, run.results.single().outcome)
        assertTrue(rig.elm.log.none { it.startsWith("ATSH") })
    }

    @Test
    fun `the confirm question names the car, the modules and what is left out`() {
        val plan = ClearPlanner.plan(Rig(SimCars.canTruck(), "Ford").report)
        val q = ScanWords.clearQuestion("2017 F-150", plan)
        assertTrue(q, q.startsWith("On the 2017 F-150, clear codes in: "))
        assertTrue(q, "ABS (brakes) (760)" in q)
        assertTrue(q, ClearPlanner.RESTRAINT_RULE in q)
        assertTrue(q, q.endsWith("Do you want me to clear?"))
    }
}
