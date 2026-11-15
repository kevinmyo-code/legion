package com.kevin.legion.vehicle

import com.kevin.legion.data.local.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the rest of the app sees the dongle's decision (OBD VIN attribution, 2026-11-15): the active
 * car, the connected car, the default for a question, the recorder's gate and the spec write.
 * Pure halves only; made-up ids.
 */
class DongleCarResolutionTest {
    private val mac = "00:11:22:33:44:55"
    private val onTruckByVin = DongleCar.OnCar(mac, "car:truck", "1A2B3C4D5E6F7G8H9", DongleCar.Basis.VIN)
    private val heldUnknown = DongleCar.Held(mac, "FAKECARVXN0000003", DongleCar.HeldReason.UNKNOWN_VIN)

    @Test
    fun `a decided car is the active car, even over a pick`() {
        assertEquals("car:truck", DongleCar.activeCarId("car:suv", mac, onTruckByVin))
    }

    @Test
    fun `a held connection is never the active car - the pick or the default is`() {
        assertEquals("car:suv", DongleCar.activeCarId("car:suv", mac, heldUnknown))
        assertEquals(VehicleController.DEFAULT_VEHICLE_ID, DongleCar.activeCarId(null, mac, heldUnknown))
    }

    @Test
    fun `undecided or unplugged keeps the old rule`() {
        assertEquals("car:suv", DongleCar.activeCarId("car:suv", mac, null))
        assertEquals(mac, DongleCar.activeCarId(null, mac, null))
        assertEquals(VehicleController.DEFAULT_VEHICLE_ID, DongleCar.activeCarId(null, null, null))
    }

    @Test
    fun `the connected car is the decision's recording id`() {
        assertEquals("car:truck", ActiveVehicle.connectedCarId(mac, "car:suv", onTruckByVin))
        assertEquals("pending:vin:FAKECARVXN0000003", ActiveVehicle.connectedCarId(mac, "car:suv", heldUnknown))
        assertNull(ActiveVehicle.connectedCarId(null, "car:suv", onTruckByVin))
    }

    private fun car(id: String) = Vehicle(obdMac = id, name = id, make = "Make", model = id, year = 2020, personaPrompt = "")

    @Test
    fun `a question naming no car skips a dongle whose car is unknown`() {
        val roster = listOf(car("car:truck"), car("car:suv"))
        val m = VehicleResolver.pickDefault(roster, "pending:obd:$mac", selectedId = "car:suv", lastConnectedId = null)
        assertEquals("car:suv", (m as VehicleMatch.Resolved).vehicle.obdMac)
    }

    @Test
    fun `nothing is recorded until the connection's car is decided`() {
        assertEquals(TelemetryRecorder.TickGuard.SKIP_UNATTRIBUTED, TelemetryRecorder.tickGuardFor(false, true, carDecided = false))
        assertEquals(TelemetryRecorder.TickGuard.PROCESS, TelemetryRecorder.tickGuardFor(false, true, carDecided = true))
        // A lost link still wins, so a drive can still end on link loss.
        assertEquals(TelemetryRecorder.TickGuard.SKIP_LINK_LOST, TelemetryRecorder.tickGuardFor(false, false, carDecided = false))
    }

    private val cars = listOf(
        DongleCar.CarVin("car:truck", "1A2B3C4D5E6F7G8H9"),
        DongleCar.CarVin("car:suv", ""),
    )
    private val labels = mapOf("car:truck" to "F-150", "car:suv" to "Outlander")

    @Test
    fun `a VIN read off the dongle is not saved onto a car it does not belong to`() {
        val other = VehicleSpecController.obdVinRefusal("1A2B3C4D5E6F7G8H9", "car:suv", cars, false, labels)
        assertTrue(other!!.contains("F-150"))
        val differs = VehicleSpecController.obdVinRefusal("9Z8Y7X6W5V4U3T2S1", "car:truck", cars, false, labels)
        assertTrue(differs!!.contains("Nothing was saved"))
        val unknown = VehicleSpecController.obdVinRefusal("9Z8Y7X6W5V4U3T2S1", "car:suv", cars, true, labels)
        assertTrue(unknown!!.contains("which car"))
    }

    @Test
    fun `a VIN that fits the car is saved`() {
        assertNull(VehicleSpecController.obdVinRefusal("9Z8Y7X6W5V4U3T2S1", "car:suv", cars, false, labels))
        assertNull(VehicleSpecController.obdVinRefusal("1A2B3C4D5E6F7G8H9", "car:truck", cars, false, labels))
    }
}
