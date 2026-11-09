package com.kevin.legion.vehicle

import com.kevin.legion.ai.CAR_STATUS_CLAUSE
import com.kevin.legion.service.LiveToolbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which car the dongle is in, and what `read_vehicle_sensor` answers from (2026-11-09 log audit,
 * A25 11-07 10:12-10:14: "not connected" and "not profiled" while live readings flowed).
 * Robolectric because [LiveToolbox]'s initializers build `org.json` objects.
 */
@RunWith(RobolectricTestRunner::class)
class ConnectedCarIdTest {

    @Test
    fun `unplugged is no car at all`() {
        assertNull(ActiveVehicle.connectedCarId(connectedAddress = null, selectedId = "car:jeep"))
    }

    @Test
    fun `a picked car is the car the dongle is in`() {
        assertEquals("car:jeep", ActiveVehicle.connectedCarId("11:22:33:44:55:66", "car:jeep"))
    }

    @Test
    fun `with no pick the dongle's own address is the car`() {
        assertEquals("11:22:33:44:55:66", ActiveVehicle.connectedCarId("11:22:33:44:55:66", null))
    }

    @Test
    fun `the connected car with no bitmask is unread, not unprofiled`() {
        val p = LiveToolbox.sensorProfile(connectedToThisCar = true, live = emptySet(), stored = emptySet())
        assertTrue(p.unread)
        assertTrue(p.pids.isEmpty())
    }

    @Test
    fun `live beats stored on the connected car, stored answers an unplugged one`() {
        assertEquals(setOf(5), LiveToolbox.sensorProfile(true, live = setOf(5), stored = setOf(12)).pids)
        assertEquals(setOf(12), LiveToolbox.sensorProfile(true, live = emptySet(), stored = setOf(12)).pids)
        val unplugged = LiveToolbox.sensorProfile(false, live = setOf(5), stored = setOf(12))
        assertEquals(setOf(12), unplugged.pids)
        assertFalse(unplugged.unread)
        assertFalse(LiveToolbox.sensorProfile(false, emptySet(), emptySet()).unread)
    }

    @Test
    fun `car status must come from ask_fleet in the turn`() {
        val c = CAR_STATUS_CLAUSE.lowercase()
        assertTrue(c.contains("ask_fleet"))
        assertTrue(c.contains("connected"))
        assertTrue(c.contains("never from this note"))
    }
}
