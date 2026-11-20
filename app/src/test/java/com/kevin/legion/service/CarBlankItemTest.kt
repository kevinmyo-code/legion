package com.kevin.legion.service

import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.FleetEngineStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
 * A `car` write with no item names nothing and writes nothing (phone audit 2026-11-19: a
 * `log_service` with no service wrote a blank service record and a blank schedule item). The
 * result asks him which one (`needs_answer`). Made-up car.
 */
@RunWith(RobolectricTestRunner::class)
class CarBlankItemTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() = runBlocking {
        RoomTestReset.resetCarDatabaseSingleton()
        FleetEngineStore.createVehicle(
            context,
            Vehicle(
                obdMac = "V1", name = "Hatch", make = "Mazda", model = "3", year = 2012,
                personaPrompt = "", odometerBaseline = 90_000, confirmed = true,
            ),
        )
        ActiveVehicle.select(context, "V1")
    }

    @After
    fun tearDown() {
        ActiveVehicle.select(context, null)
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun call(name: String, args: JSONObject): JSONObject = runBlocking {
        LiveToolbox.dispatch(context, name, args)!!
    }

    private fun assertAsksAndWroteNothing(r: JSONObject) = runBlocking {
        assertFalse(r.toString(), r.getBoolean("success"))
        assertTrue(r.toString(), r.optBoolean("needs_answer"))
        assertTrue(r.toString(), r.getString("message").startsWith("Nothing was logged"))
        assertEquals(0, FleetEngineStore.getRecentForVehicle(context, "V1", 10).size)
        assertTrue(FleetEngineStore.getForVehicle(context, "V1").none { it.serviceName.isBlank() })
    }

    @Test
    fun `log_service with no service writes nothing and asks which`() {
        assertAsksAndWroteNothing(call("log_service", JSONObject()))
    }

    @Test
    fun `log_service with a blank service writes nothing and asks which`() {
        assertAsksAndWroteNothing(call("log_service", JSONObject().put("service", "   ")))
    }

    @Test
    fun `the folded car tool refuses a blank log_service the same way`() {
        assertAsksAndWroteNothing(call("car", JSONObject().put("action", "log_service").put("service", "")))
    }

    @Test
    fun `set_maintenance_interval with a blank service writes nothing and asks which`() {
        assertAsksAndWroteNothing(
            call("set_maintenance_interval", JSONObject().put("service", "").put("interval_miles", 5000)),
        )
    }

    @Test
    fun `log_past_service and log_build_entry with no item ask which`() {
        val past = call("log_past_service", JSONObject().put("service", " ").put("miles_ago", 100))
        assertAsksAndWroteNothing(past)
        val build = call("log_build_entry", JSONObject().put("title", ""))
        assertFalse(build.getBoolean("success"))
        assertTrue(build.toString(), build.optBoolean("needs_answer"))
    }

    @Test
    fun `a named service still logs`() = runBlocking {
        val r = call("log_service", JSONObject().put("service", "oil change"))
        assertTrue(r.toString(), r.getBoolean("success"))
        assertEquals(1, FleetEngineStore.getRecentForVehicle(context, "V1", 10).size)
    }
}
