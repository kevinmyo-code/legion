@file:Suppress("MaxLineLength") // invented wire frames read best on one line

package com.kevin.legion.service

import com.kevin.legion.ai.AgentResult
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.service.cascade.CascadePrefix
import com.kevin.legion.service.cascade.CascadeRules
import com.kevin.legion.service.cascade.ClosingRound
import com.kevin.legion.service.cascade.GeminiStreamTransport
import com.kevin.legion.service.cascade.GeminiTextLoop
import com.kevin.legion.service.cascade.LoneWordWriteGuard
import com.kevin.legion.service.cascade.ScriptedLines
import com.kevin.legion.service.cascade.StreamOutcome
import com.kevin.legion.service.tools.LiveSurface
import com.kevin.legion.service.tools.day.RelativeDay
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.AdapterBinding
import com.kevin.legion.vehicle.CarFingerprint
import com.kevin.legion.vehicle.DongleCar
import com.kevin.legion.vehicle.DongleCarController
import com.kevin.legion.vehicle.FleetEngineStore
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.ObdTransport
import com.kevin.legion.vehicle.VinResponse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
import java.time.LocalDate
import java.time.LocalTime

/**
 * Where the two 2026-11-19 phone branches meet: the adapter's car per connection
 * (feat/obd-adapter-per-connection) and every completed change said by code
 * (fix/phone-write-always-said). Real results from the real tools ([LiveToolbox.dispatch], the
 * adapter's controller), called by their surfaced names as the model calls them, then through
 * [ScriptedLines], [LoneWordWriteGuard] and [ClosingRound]. Invented cars, MAC and print.
 */
@RunWith(RobolectricTestRunner::class)
class AdapterDoneLineTest {
    private val context = RuntimeEnvironment.getApplication()
    private val mac = "00:11:22:33:44:77"
    private val print = "p6|410012345679"

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

    // --- fixtures (as AdapterCarVoiceTest) ------------------------------------------------------------

    private suspend fun seed() {
        for ((id, name) in listOf("car:zephyr" to "Zephyr", "car:comet" to "Comet")) {
            FleetEngineStore.createVehicle(
                context,
                Vehicle(obdMac = id, name = name, make = "Acme", model = name, year = 2010, personaPrompt = ""),
            )
        }
    }

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

    private fun heardDates() = RelativeDay.TurnDates(LocalDate.now(), LocalTime.now(), onlyDateCall = true)

    /** A surfaced call ("car adapter_in"), resolved and dispatched as a voice turn makes it. */
    private suspend fun surfaced(name: String, vararg kv: Pair<String, String>): JSONObject {
        val args = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }
        val r = LiveSurface.resolve(name, args)
        return withContext(heardDates()) { LiveToolbox.dispatch(context, r.name, r.args)!! }
    }

    /** What code says for that call's result, judged as the cascade judges it. */
    private fun line(name: String, response: JSONObject, vararg kv: Pair<String, String>): String? {
        val args = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }
        assertTrue("$name ${args.optString("action")} is a write", CascadeRules.isWrite(name, args))
        val fact = ScriptedLines.factOf(name, args, response, response.optBoolean("success"), confirmed = true)
        return ScriptedLines.doneLine(listOf(fact))
    }

    /** A drive on the Zephyr (he named it on this connection), with one live reading filed under it. */
    private suspend fun zephyrDrive() {
        seed()
        DongleCarController.onConnected(context, mac, print) { VinResponse.Result.NoData }
        assertTrue(DongleCarController.answer(context, "car:zephyr").ok)
        setLink(mac, withLink = true)
        com.kevin.legion.data.local.CarDatabase.getDatabase(context).odbSampleDao().insert(
            com.kevin.legion.data.local.OdbSample(
                vehicleId = "car:zephyr", pid = "010C", value = 800.0, unit = "rpm",
                timestamp = System.currentTimeMillis() + 1,
            ),
        )
    }

    // --- a. adapter_in's real result has its own code line ----------------------------------------

    @Test
    fun `adapter_in that moved this drive - code says the drive now goes under the car`() = runBlocking {
        zephyrDrive()
        val r = surfaced("car", "action" to "adapter_in", "vehicle" to "Comet")
        assertTrue(r.toString(), r.getBoolean("readings_moved"))
        assertEquals(
            "Done: this drive now goes under the ${r.getString("vehicle")}.",
            line("car", r, "action" to "adapter_in", "vehicle" to "Comet"),
        )
    }

    @Test
    fun `adapter_in on a wrongly recognised car - every rebind moves this drive, so that is what is said`() = runBlocking {
        // Recognised as the Zephyr by its print, nothing filed yet. The controller only ever rebinds by
        // moving the drive (moved even with 0 readings), so "the adapter is on the X" (changed without
        // readings_moved) has no real path today: this pins what he does hear.
        seed()
        CarFingerprint.save(context, "car:zephyr", print)
        DongleCarController.onConnected(context, mac, print) { VinResponse.Result.NoData }
        setLink(mac, withLink = true)

        val r = surfaced("car", "action" to "adapter_in", "vehicle" to "Comet")
        assertTrue(r.toString(), r.getBoolean("changed"))
        assertEquals(
            "Done: this drive now goes under the ${r.getString("vehicle")}.",
            line("car", r, "action" to "adapter_in", "vehicle" to "Comet"),
        )
    }

    @Test
    fun `adapter_in confirming the recognised car - changed false, code says nothing`() = runBlocking {
        seed()
        CarFingerprint.save(context, "car:zephyr", print)
        DongleCarController.onConnected(context, mac, print) { VinResponse.Result.NoData }
        setLink(mac, withLink = true)

        val r = surfaced("car", "action" to "adapter_in", "vehicle" to "Zephyr")
        assertTrue(r.toString(), r.getBoolean("success"))
        assertFalse(r.getBoolean("changed"))
        assertNull(line("car", r, "action" to "adapter_in", "vehicle" to "Zephyr"))
    }

    @Test
    fun `adapter_in to the car it is already on - code says nothing`() = runBlocking {
        zephyrDrive()
        val r = surfaced("car", "action" to "adapter_in", "vehicle" to "Zephyr")
        assertTrue(r.toString(), r.getBoolean("success"))
        assertFalse(r.getBoolean("changed"))
        assertNull(line("car", r, "action" to "adapter_in", "vehicle" to "Zephyr"))
    }

    // --- b. the car already selected ------------------------------------------------------------------

    @Test
    fun `switch_vehicle to the car already selected - changed false and code says nothing`() = runBlocking {
        seed()
        ActiveVehicle.select(context, "car:zephyr")
        val r = surfaced("car", "action" to "switch_vehicle", "vehicle" to "Zephyr")
        assertTrue(r.toString(), r.getBoolean("success"))
        assertFalse(r.getBoolean("changed"))
        assertNull(line("car", r, "action" to "switch_vehicle", "vehicle" to "Zephyr"))
    }

    @Test
    fun `switch_vehicle to the car the adapter is already on - code says nothing`() = runBlocking {
        zephyrDrive()
        ActiveVehicle.select(context, "car:zephyr")
        val r = surfaced("car", "action" to "switch_vehicle", "vehicle" to "Zephyr")
        assertFalse(r.toString(), r.getBoolean("changed"))
        assertNull(line("car", r, "action" to "switch_vehicle", "vehicle" to "Zephyr"))
    }

    // --- c. the which-car ask is an ask -----------------------------------------------------------------

    /** A plain pick while the adapter's car is unknown: a real change that also carries the one ask. */
    private suspend fun pickWhileHeld(): JSONObject {
        seed()
        DongleCarController.onConnected(context, mac, null) { VinResponse.Result.NoData }
        setLink(mac, withLink = true)
        val r = surfaced("car", "action" to "switch_vehicle", "vehicle" to "Comet")
        assertTrue(DongleCarController.state.value is DongleCar.Held)
        assertTrue(r.toString(), r.getBoolean(AdapterBinding.NEEDS_ANSWER))
        return r
    }

    @Test
    fun `the which-car ask - the pick is said, the closing text is kept, his one word next is an answer`() =
        runBlocking {
            val r = pickWhileHeld()
            val args = JSONObject().put("action", "switch_vehicle").put("vehicle", "Comet")
            assertEquals("Done: switched to the ${r.getString("vehicle")}.", line("car", r, "action" to "switch_vehicle", "vehicle" to "Comet"))
            val fact = ScriptedLines.factOf("car", args, r, ok = true, confirmed = true)
            assertFalse("the model's question must be spoken", ClosingRound.dropsText(listOf(ClosingRound.Call(fact, r))))
            val asked = LoneWordWriteGuard.asks("car", args, r)
            assertTrue(asked)
            val answer = JSONObject().put("action", "adapter_in").put("vehicle", "Comet")
            assertNull(LoneWordWriteGuard.refusal("car", answer, "Comet.", "", previousAsked = asked))
        }

    @Test
    fun `the which-car ask - a model question naming the car code just said is still spoken`() = runBlocking {
        seed()
        DongleCarController.onConnected(context, mac, null) { VinResponse.Result.NoData }
        setLink(mac, withLink = true)
        // The model names the cars by the labels it was given: the one code just said, in full.
        val label = com.kevin.legion.vehicle.VehicleController.label(FleetEngineStore.getByMac(context, "car:comet")!!)
        val question = "Which car is the adapter in, the 2010 Acme Zephyr or the $label?"
        val transport = Scripted(
            calls(call("manage_vehicle", "action" to "switch", "vehicle" to "Comet")),
            text(question),
        )
        val heard = StringBuilder()
        val codeLines = mutableListOf<String>()
        val listener = object : GeminiTextLoop.Listener {
            override fun onText(delta: String) {
                heard.append(delta)
            }
            override fun onCodeLine(text: String, facts: List<ScriptedLines.WriteFact>) {
                codeLines.add(text)
            }
        }
        val loop = GeminiTextLoop(
            transport = transport,
            cache = null,
            classify = { _ -> AgentResult.Failed },
            isMutating = { n, a -> CascadeRules.isWrite(n, a) },
            isReadThrough = { _, _ -> false },
            writeFact = ScriptedLines::factOf,
        )
        val tools =TextToolExecutor { name, args, _ -> withContext(heardDates()) { LiveToolbox.dispatch(context, name, args)!! } }
        loop.answer(CascadePrefix("model-x", "Invented instruction.", JSONArray()), userTurn(), tools, listener)
        assertEquals(listOf("Done: switched to the $label."), codeLines)
        assertTrue("the which-car question was dropped: $heard", heard.contains(question))
    }

    // --- wire --------------------------------------------------------------------------------------------

    private fun userTurn() = JSONArray().put(
        JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "invented"))),
    )

    private fun parts(vararg p: JSONObject) = JSONObject().put(
        "candidates",
        JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", JSONArray(p.toList())))),
    )

    private fun text(t: String) = parts(JSONObject().put("text", t))

    private fun call(name: String, vararg kv: Pair<String, String>) =
        JSONObject().put("functionCall", JSONObject().put("name", name).put("args", JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }))

    private fun calls(vararg c: JSONObject) = parts(*c)

    /** Answers each request with the next frame. */
    private class Scripted(vararg frames: JSONObject) : GeminiStreamTransport {
        private val queue = ArrayDeque(frames.toList())
        override suspend fun stream(model: String, body: JSONObject, onEvent: (JSONObject) -> Unit): StreamOutcome {
            queue.removeFirstOrNull()?.let(onEvent)
            return StreamOutcome.Ok
        }
    }
}
