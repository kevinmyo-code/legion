package com.kevin.legion.service

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.FleetEngineStore
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.ObdTransport
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
import java.io.ByteArrayInputStream
import java.io.OutputStream

/**
 * [LiveToolbox.dispatch]'s vehicle-scoping behaviour (fleet-wide voice, ticket
 * 01). Covers tests 9-12 of the ticket's own numbered list, plus verification
 * gate 3 (a category B call with a `vehicle` argument must NEVER move
 * [ActiveVehicle.current]).
 *
 * Same Robolectric-plus-Room shape as [com.kevin.legion.vehicle.VehicleResolverTest];
 * see that file's doc for why [clearState] wipes the vehicles table and prefs
 * before every test rather than trusting per-method isolation.
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolboxVehicleScopingTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        context.getSharedPreferences("active_vehicle", android.content.Context.MODE_PRIVATE).edit().clear().apply()
        clearObdConnection()
    }

    @After
    fun tearDown() {
        // Drains ArchTaskExecutor's disk-IO pool before anything else in this @After - see
        // RoomTestReset's class doc comment and
        // .scratch/hardening/issues/13-the-suite-is-green-by-luck.md: a DAO write earlier in
        // this test can leave a Room InvalidationTracker refresh in flight, and it must finish
        // before this test method returns or it races Robolectric's per-method reset.
        RoomTestReset.drainArchDiskIoPool()

        clearObdConnection()
    }

    private fun vehicle(obdMac: String, name: String, odometerBaseline: Int = 2000) = Vehicle(
        obdMac = obdMac, name = name, make = "Test", model = "Car", year = 2020,
        personaPrompt = "", odometerBaseline = odometerBaseline, odometerBaselineAt = 1L,
    )

    // Cutover 4 (docs/architecture/cutover4-2026-08-24.md): both fixtures write through
    // FleetEngineStore now - every FleetEngineStore write resolves the vehicle by a real ENGINE
    // record, which the legacy-only vehicleDao()/maintenanceItemDao() upserts this file used to
    // call would leave unresolvable.
    private suspend fun seedVehicles(vararg vehicles: Vehicle) {
        for (v in vehicles) FleetEngineStore.createVehicle(context, v)
    }

    private suspend fun seedItem(vehicleId: String, serviceName: String) {
        FleetEngineStore.upsertNewItem(
            context,
            MaintenanceItem(
                vehicleId = vehicleId, serviceName = serviceName,
                intervalMiles = 5000, lastDoneMileage = 1000,
            )
        )
    }

    // --- Reflection helpers to fake an OBD connection (ObdBluetoothManager
    // exposes no public setter - `transport` is private, `connectedDeviceAddress`
    // has a private set - both by design, since only real Bluetooth/TCP connect
    // code should ever touch them outside a test). A no-op ObdTransport is
    // enough to make `isConnected` true. ---

    private fun fakeConnect(mac: String) {
        val transportField = ObdBluetoothManager::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        transportField.set(ObdBluetoothManager, object : ObdTransport {
            override val inputStream = ByteArrayInputStream(ByteArray(0))
            override val outputStream = object : OutputStream() { override fun write(b: Int) {} }
            override val label = mac
            override fun close() {}
        })
        val addressField = ObdBluetoothManager::class.java.getDeclaredField("connectedDeviceAddress")
        addressField.isAccessible = true
        addressField.set(ObdBluetoothManager, mac)
    }

    private fun clearObdConnection() {
        val transportField = ObdBluetoothManager::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        transportField.set(ObdBluetoothManager, null)
        val addressField = ObdBluetoothManager::class.java.getDeclaredField("connectedDeviceAddress")
        addressField.isAccessible = true
        addressField.set(ObdBluetoothManager, null)
    }

    /** Test 9: a category B tool with `vehicle` omitted returns the active car's data (no behaviour change). */
    @Test
    fun `category B tool with vehicle omitted answers for the active car`() = runBlocking {
        seedVehicles(vehicle("AA:BB", "Outlander"), vehicle("CC:DD", "Miata"))
        seedItem("AA:BB", "Oil Change A")
        seedItem("CC:DD", "Tire Rotation B")
        ActiveVehicle.select(context, "AA:BB")

        val result = LiveToolbox.dispatch(context, "get_next_service", JSONObject())!!

        assertTrue(result.getBoolean("success"))
        assertTrue(result.getString("message").contains("Oil Change A"))
        assertFalse(result.getString("message").contains("Tire Rotation B"))
    }

    /**
     * Test 10: a category B tool with `vehicle` naming the other car returns
     * THAT car's data while [ActiveVehicle.current] is unchanged after the
     * call - verification gate 3.
     */
    @Test
    fun `category B tool with a named other car answers for it without moving the active car`() = runBlocking {
        seedVehicles(vehicle("AA:BB", "Outlander"), vehicle("CC:DD", "Miata"))
        seedItem("AA:BB", "Oil Change A")
        seedItem("CC:DD", "Tire Rotation B")
        ActiveVehicle.select(context, "AA:BB")

        val result = LiveToolbox.dispatch(context, "get_next_service", JSONObject().put("vehicle", "Miata"))!!

        assertTrue(result.getBoolean("success"))
        assertTrue(result.getString("message").contains("Tire Rotation B"))
        assertFalse(result.getString("message").contains("Oil Change A"))
        // Gate 3: resolving "the other car" must never switch which one is active.
        assertEquals("AA:BB", ActiveVehicle.current(context))
    }

    /**
     * Test 11: a category A tool given a `vehicle` that is not the connected
     * car refuses, and the refusal names the car the dongle IS in. Must NOT
     * return the active car's readings.
     */
    @Test
    fun `category A tool refuses a vehicle argument naming a car the dongle is not in`() = runBlocking {
        seedVehicles(vehicle("AA:BB", "Outlander"), vehicle("CC:DD", "Miata"))
        // The dongle is physically in the Outlander; the Miata is merely
        // registered, not connected. Active car is deliberately the SAME as
        // connected here, so a failure that fell back to "the active car's
        // data" would be indistinguishable from a correct refusal unless we
        // assert on the actual JSON shape, not just success/failure.
        ActiveVehicle.select(context, "AA:BB")
        fakeConnect("AA:BB")

        val result = LiveToolbox.dispatch(context, "get_health", JSONObject().put("vehicle", "Miata"))!!

        assertFalse(result.getBoolean("success"))
        assertEquals("not_connected_to_that_car", result.getString("error"))
        assertTrue(result.getString("message").contains("Outlander"))
        // Must not silently carry the active/connected car's real reading under
        // the wrong label - the refusal JSON must not smuggle a "connected"/
        // health-data shape in alongside the error.
        assertFalse(result.has("connected"))
        assertFalse(result.has("batteryVolts"))
    }

    /** Test 12: list_vehicles returns every non-archived car and flags exactly one active. */
    @Test
    fun `list_vehicles returns every non-archived car and flags exactly one active`() = runBlocking {
        seedVehicles(
            vehicle("AA:BB", "Outlander"),
            vehicle("CC:DD", "Miata"),
            vehicle("EE:FF", "Retired").copy(archived = true),
        )
        ActiveVehicle.select(context, "CC:DD")

        val result = LiveToolbox.dispatch(context, "list_vehicles", JSONObject())!!

        assertTrue(result.getBoolean("success"))
        assertEquals(2, result.getInt("count"))
        val arr = result.getJSONArray("vehicles")
        var activeCount = 0
        var activeName = ""
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            assertFalse("archived car must be excluded", o.getString("name") == "Retired")
            if (o.getBoolean("active")) {
                activeCount++
                activeName = o.getString("name")
            }
        }
        assertEquals(1, activeCount)
        assertEquals("Miata", activeName)
    }
}
