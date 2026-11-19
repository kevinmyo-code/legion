package com.kevin.legion.service

import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.AdapterBinding
import com.kevin.legion.vehicle.CarFingerprint
import com.kevin.legion.vehicle.DongleCar
import com.kevin.legion.vehicle.DongleCarController
import com.kevin.legion.vehicle.DtcClearController
import com.kevin.legion.vehicle.FleetEngineStore
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.ObdTransport
import com.kevin.legion.vehicle.VinResponse
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.OutputStream

/**
 * What the voice is told while the OBD adapter's car is unknown or only recognised (one adapter moved
 * between cars, Kevin 2026-11-17), through [LiveToolbox.dispatch]. Invented cars, MAC and print.
 */
@RunWith(RobolectricTestRunner::class)
class AdapterCarVoiceTest {
    private val context = RuntimeEnvironment.getApplication()
    private val mac = "00:11:22:33:44:66"
    private val print = "p6|410012345678"

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        DongleCarController.resetForTest()
        AdapterBinding.resetForTest()
        setLink(null, null)
    }

    @After
    fun tearDown() {
        RoomTestReset.drainArchDiskIoPool()
        DongleCarController.resetForTest()
        AdapterBinding.resetForTest()
        setLink(null, null)
    }

    /** A tool call as a voice turn makes it: the turn's date facts ride in its context, as on the phone. */
    private suspend fun heard(name: String, args: JSONObject = JSONObject(), userText: String? = null): JSONObject =
        kotlinx.coroutines.withContext(
            com.kevin.legion.service.tools.day.RelativeDay.TurnDates(
                java.time.LocalDate.now(), java.time.LocalTime.now(), onlyDateCall = true,
            ),
        ) { LiveToolbox.dispatch(context, name, args, userText = userText)!! }

    private suspend fun seed() {
        for ((id, name) in listOf("car:zephyr" to "Zephyr", "car:comet" to "Comet")) {
            FleetEngineStore.createVehicle(
                context,
                Vehicle(obdMac = id, name = name, make = "Acme", model = name, year = 2010, personaPrompt = ""),
            )
        }
    }

    /** The adapter's address, and a link only when [withLink] (a link with nothing behind it). */
    private fun setLink(address: String?, withLink: Boolean?) {
        val transport = ObdBluetoothManager::class.java.getDeclaredField("transport").apply { isAccessible = true }
        transport.set(
            ObdBluetoothManager,
            if (withLink == true) {
                object : ObdTransport {
                    override val inputStream = ByteArrayInputStream(ByteArray(0))
                    override val outputStream = object : OutputStream() { override fun write(b: Int) = Unit }
                    override val label = mac
                    override fun close() = Unit
                }
            } else {
                null
            },
        )
        ObdBluetoothManager::class.java.getDeclaredField("connectedDeviceAddress").apply { isAccessible = true }
            .set(ObdBluetoothManager, address)
    }

    @Test
    fun `an unknown car - a live-car result asks once a connection, then says he was asked`() = runBlocking {
        seed()
        // Nothing speaks here: the connect-time ask is only posted, so it does not use up the one ask (A8).
        assertTrue(DongleCarController.onConnected(context, mac, null) { VinResponse.Result.NoData } is DongleCar.Held)
        setLink(mac, withLink = false)

        val first = heard("get_codes")
        assertEquals(AdapterBinding.ASK_ONCE, first.getString("adapter_car"))
        assertTrue(first.getBoolean(AdapterBinding.NEEDS_ANSWER))
        val second = heard("get_codes")
        assertEquals(AdapterBinding.ASKED, second.getString("adapter_car"))
        assertFalse(second.has(AdapterBinding.NEEDS_ANSWER))
    }

    @Test
    fun `a background read never uses up the ask, and gets no note`() = runBlocking {
        seed()
        DongleCarController.onConnected(context, mac, null) { VinResponse.Result.NoData }
        setLink(mac, withLink = false)

        val background = LiveToolbox.dispatch(context, "get_codes", JSONObject())!!
        assertFalse(background.has("adapter_car"))
        assertEquals(AdapterBinding.ASK_ONCE, heard("get_codes").getString("adapter_car"))
    }

    @Test
    fun `the fleet specialist's answer carries the ask, once`() = runBlocking {
        seed()
        DongleCarController.onConnected(context, mac, null) { VinResponse.Result.NoData }
        setLink(mac, withLink = false)

        // No key in the test JVM: the specialist answers at once that it could not run.
        val r = heard("ask_fleet", JSONObject().put("question", "how is the car doing right now?"))
        assertEquals(r.toString(), AdapterBinding.ASK_ONCE, r.getString("adapter_car"))
        assertTrue(r.getBoolean(AdapterBinding.NEEDS_ANSWER))
        assertEquals(AdapterBinding.ASKED, heard("get_codes").getString("adapter_car"))
    }

    @Test
    fun `an unknown car - a read for a named car is refused with how to name it, never a dead end`() = runBlocking {
        seed()
        DongleCarController.onConnected(context, mac, null) { VinResponse.Result.NoData }
        setLink(mac, withLink = true)

        val r = heard("get_health", JSONObject().put("vehicle", "Comet"))
        assertFalse(r.getBoolean("success"))
        assertFalse(r.has(AdapterBinding.NEEDS_ANSWER))
        assertEquals(AdapterBinding.UNKNOWN_CAR_ERROR, r.getString("error"))
        assertTrue(r.getString("message").contains("car adapter_in vehicle=Comet"))
        assertFalse(r.has("adapter_car"))
        assertFalse(r.has("batteryVolts"))
    }

    @Test
    fun `a recognised car - said once a connection as a recognition, not a VIN`() = runBlocking {
        seed()
        CarFingerprint.save(context, "car:zephyr", print)
        val d = DongleCarController.onConnected(context, mac, print) { VinResponse.Result.NoData } as DongleCar.OnCar
        assertEquals(DongleCar.Basis.ASSUMED, d.basis)
        setLink(mac, withLink = false)

        val first = heard("get_codes")
        val note = first.getString("adapter_car")
        assertFalse(first.has(AdapterBinding.NEEDS_ANSWER))
        assertTrue(note, note.contains("Looks like the") && note.contains("Zephyr") && note.contains("not by a VIN"))
        assertFalse(heard("get_codes").has("adapter_car"))
        assertEquals("car:zephyr", ActiveVehicle.recordingId(context))
    }

    @Test
    fun `clearing codes is refused for a recognised car, by voice and on the Faults screen, until he names it`() =
        runBlocking {
            seed()
            CarFingerprint.save(context, "car:zephyr", print)
            DongleCarController.onConnected(context, mac, print) { VinResponse.Result.NoData }
            setLink(mac, withLink = true)

            val voice = LiveToolbox.dispatch(
                context, "clear_codes", JSONObject().put("confirmed", false), userText = "clear the codes",
            )!!
            assertFalse(voice.getBoolean("success"))
            assertFalse(voice.has(AdapterBinding.NEEDS_ANSWER))
            assertTrue(voice.getString("message"), voice.getString("message").startsWith("Nothing was cleared"))
            val zephyr = FleetEngineStore.getByMac(context, "car:zephyr")!!
            val screen = DtcClearController.dispatchAndRecord(context, zephyr, confirmed = true)
            assertEquals(DtcClearController.ClearOutcome.REFUSED, screen.outcome)
            assertTrue(screen.message, screen.message.startsWith("Nothing was cleared"))

            assertTrue(DongleCarController.answer(context, "car:zephyr").ok)
            assertNull(AdapterBinding.clearRefusalNow())
        }

    private suspend fun switchTo(name: String): JSONObject = LiveToolbox.dispatch(
        context, "manage_vehicle", JSONObject().put("action", "switch").put("vehicle", name),
    )!!

    @Test
    fun `switch - the car already selected with nothing connected is changed=false, with its label`() = runBlocking {
        seed()
        ActiveVehicle.select(context, "car:zephyr")
        val r = switchTo("Zephyr")
        assertTrue(r.getBoolean("success"))
        assertFalse(r.getBoolean("changed"))
        assertTrue(r.getString("vehicle"), r.getString("vehicle").contains("Zephyr"))
    }

    @Test
    fun `switch - another car with nothing connected is changed=true`() = runBlocking {
        seed()
        ActiveVehicle.select(context, "car:zephyr")
        val r = switchTo("Comet")
        assertTrue(r.getBoolean("success"))
        assertTrue(r.getBoolean("changed"))
        assertTrue(r.getString("vehicle").contains("Comet"))
        assertEquals("car:comet", ActiveVehicle.selected(context))
    }

    private suspend fun adapterIn(name: String): JSONObject = LiveToolbox.dispatch(
        context, "manage_vehicle", JSONObject().put("action", "adapter").put("vehicle", name),
    )!!

    private suspend fun zephyrDrive(): Long {
        seed()
        DongleCarController.onConnected(context, mac, print) { VinResponse.Result.NoData }
        assertTrue(DongleCarController.answer(context, "car:zephyr").ok)
        setLink(mac, withLink = true)
        val db = com.kevin.legion.data.local.CarDatabase.getDatabase(context)
        db.odbSampleDao().insert(
            com.kevin.legion.data.local.OdbSample(
                vehicleId = "car:zephyr", pid = "010C", value = 800.0, unit = "rpm",
                timestamp = System.currentTimeMillis() + 1,
            ),
        )
        return db.odbSampleDao().totalCount("car:zephyr").toLong()
    }

    @Test
    fun `switch to another car mid-drive after he named it - the pick changes, the drive stays`() = runBlocking {
        assertEquals(1L, zephyrDrive())

        val r = switchTo("Comet")
        assertTrue(r.toString(), r.getBoolean("success"))
        assertTrue(r.getBoolean("changed"))
        assertTrue(r.getString("message"), r.getString("message").contains("stored data"))
        assertEquals("car:comet", ActiveVehicle.selected(context))
        val live = DongleCarController.state.value as DongleCar.OnCar
        assertEquals("car:zephyr", live.carId)
        val db = com.kevin.legion.data.local.CarDatabase.getDatabase(context)
        assertEquals(1, db.odbSampleDao().totalCount("car:zephyr"))
        assertEquals(0, db.odbSampleDao().totalCount("car:comet"))
    }

    @Test
    fun `the adapter is in another car - his correction rebinds the drive and says its readings moved`() =
        runBlocking {
            zephyrDrive()

            val r = adapterIn("Comet")
            assertTrue(r.toString(), r.getBoolean("success"))
            assertTrue(r.getBoolean("changed"))
            assertTrue(r.getBoolean("readings_moved"))
            assertTrue(r.getString("vehicle").contains("Comet"))
            val live = DongleCarController.state.value as DongleCar.OnCar
            assertEquals(DongleCar.OnCar(mac, "car:comet", null, DongleCar.Basis.ANSWER), live)
            val db = com.kevin.legion.data.local.CarDatabase.getDatabase(context)
            assertEquals(1, db.odbSampleDao().totalCount("car:comet"))
        }

    @Test
    fun `the adapter is in the car it is already bound to - changed=false, nothing moved`() = runBlocking {
        zephyrDrive()
        val r = adapterIn("Zephyr")
        assertTrue(r.toString(), r.getBoolean("success"))
        assertFalse(r.getBoolean("changed"))
        assertFalse(r.getBoolean("readings_moved"))
    }

    @Test
    fun `switch - a wrong recognition is corrected by voice`() = runBlocking {
        seed()
        CarFingerprint.save(context, "car:zephyr", print)
        DongleCarController.onConnected(context, mac, print) { VinResponse.Result.NoData }
        setLink(mac, withLink = true)

        val r = switchTo("Comet")
        assertTrue(r.toString(), r.getBoolean("changed"))
        assertEquals("car:comet", (DongleCarController.state.value as DongleCar.OnCar).carId)
    }

    @Test
    fun `switch - the car the adapter is already bound to is changed=false`() = runBlocking {
        seed()
        DongleCarController.onConnected(context, mac, print) { VinResponse.Result.NoData }
        DongleCarController.answer(context, "car:zephyr")
        ActiveVehicle.select(context, "car:zephyr")
        setLink(mac, withLink = true)
        assertFalse(switchTo("Zephyr").getBoolean("changed"))
    }

    @Test
    fun `a write to this car is refused while the adapter's car is unknown, never filed on a guess`() = runBlocking {
        seed()
        ActiveVehicle.select(context, "car:zephyr")
        DongleCarController.onConnected(context, mac, null) { VinResponse.Result.NoData }
        setLink(mac, withLink = true)

        val r = heard("set_odometer", JSONObject().put("miles", 12345))
        assertFalse(r.getBoolean("success"))
        assertFalse(r.has(AdapterBinding.NEEDS_ANSWER))
        assertTrue(r.getString("message"), r.getString("message").startsWith("Nothing was saved"))
        // Named, it is not this refusal.
        val namedArgs = JSONObject().put("miles", 12345).put("vehicle", "Comet")
        val named = LiveToolbox.dispatch(context, "set_odometer", namedArgs)!!
        assertFalse(named.getString("message"), named.getString("message").contains("has not named yet"))
    }

    @Test
    fun `this car is a car already on file - names the adapter's car instead of adding one`() = runBlocking {
        seed()
        DongleCarController.onConnected(context, mac, print) { VinResponse.Result.NoData }
        setLink(mac, withLink = true)

        val out = com.kevin.legion.vehicle.VehicleController.registerDirect(context, 2010, "Acme", "Comet")
        assertTrue(out.message, out.success)
        assertTrue(out.message.startsWith("That car is already on file"))
        assertEquals("car:comet", (DongleCarController.state.value as DongleCar.OnCar).carId)
        assertEquals("car:comet", DongleCarController.recognised(context, print))
    }

    @Test
    fun `a yes from another connection is refused, and the refusal asks nothing`() = runBlocking {
        seed()
        DongleCarController.onConnected(context, mac, print) { VinResponse.Result.NoData }
        DongleCarController.answer(context, "car:zephyr")
        setLink(mac, withLink = true)
        DtcClearController.voiceGate.asked("clear the codes", "c0|an earlier connection")

        val r = heard("clear_codes", JSONObject().put("confirmed", true), userText = "yes")
        assertFalse(r.getBoolean("success"))
        assertEquals(com.kevin.legion.vehicle.ClearCodesVoiceGate.OTHER_CAR, r.getString("message"))
        assertFalse(r.has(AdapterBinding.NEEDS_ANSWER))
        DtcClearController.voiceGate.reset()
    }

    @Test
    fun `a car he named on this connection gets no note`() = runBlocking {
        seed()
        DongleCarController.onConnected(context, mac, print) { VinResponse.Result.NoData }
        DongleCarController.answer(context, "car:comet")
        setLink(mac, withLink = false)
        assertFalse(heard("get_codes").has("adapter_car"))
    }
}
