package com.kevin.legion.carlink

import com.kevin.legion.carlink.protocol.DirectionKeys
import com.kevin.legion.carlink.protocol.FakeUnit
import com.kevin.legion.carlink.protocol.FixSample
import com.kevin.legion.carlink.protocol.Mnai2
import com.kevin.legion.carlink.protocol.Mnai2Feed
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Forget against a real sender loop on a real thread, with a UDP port that blocks like a socket. */
class CarLinkControllerTest {
    private val key = ByteArray(32) { 5 }
    private val unitAddr = InetAddress.getByName("10.0.0.1")

    /** Blocks in [receive] like DatagramSocket; [close] makes a blocked receive throw, as Android's does. */
    private class BlockingPort : DatagramPort {
        val inbox = LinkedBlockingQueue<ByteArray>()
        val sent: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        @Volatile var closed = false
        @Volatile var from = "10.0.0.1"

        override fun receive(buf: ByteArray, timeoutMs: Int): Incoming? {
            if (closed) throw SocketException("closed")
            val d = inbox.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (closed || d === POISON) throw SocketException("closed")
            d ?: return null
            d.copyInto(buf)
            return Incoming(d.size, InetAddress.getByName(from), 40_000)
        }

        override fun send(bytes: ByteArray, to: InetAddress, port: Int) { sent += bytes }

        override fun close() {
            closed = true
            inbox.put(POISON)
        }

        companion object {
            val POISON = ByteArray(0)
        }
    }

    private val errors: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> errors += e })
    private var port = BlockingPort()
    private val store = CarLinkStore(FakeKeystoreStandIn(), MemoryRecordFile()) {}
    private val lan = PairingNetwork("ap-test0", "10.0.0.0", 24)

    private var look: NetworkLook = NetworkLook.Present
    private var hotspotNow = true
    private var cars = listOf(VehicleOption("car-a-id", "Car A"), VehicleOption("car-b-id", "Car B"))
    private val service = FakeServiceHost()

    /** Records the foreground service's starts and stops. */
    private class FakeServiceHost : LocationServiceHost {
        @Volatile var running = false
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun start(): Boolean { running = true; log += "start"; return true }
        override fun stop() { running = false; log += "stop" }
    }
    private var transport = com.kevin.legion.carlink.protocol.PairingTransport { _, _ -> null }
    private var wallNow = 1_000_000_000_000L
    private var senders = 0

    private fun controller(store: CarLinkStore = this.store) = CarLinkController(
        store = store,
        transport = { h, p -> transport.connect(h, p) },
        handover = HandoverPlan(HandoverSources({ null }, { null }, { null }), UnitTokenNotYetAvailable()) {},
        newSender = { ring, onEvent, sendFixes, mode ->
            if (senders > 0) port = BlockingPort()
            senders++
            CarLinkSender(
                feed = Mnai2Feed(
                    ring, { ByteArray(16) { 3 } }, mode.healOn?.let { h -> h::contains } ?: lan::contains,
                    { null }, onEvent,
                ),
                port = port,
                clock = { System.nanoTime() / 1_000_000 },
                fixes = object : FixSource {
                    override fun latest(): FixSample? = null
                    override fun holdFast(on: Boolean) = Unit
                },
                sendFixes = sendFixes,
            )
        },
        scope = scope,
        io = Dispatchers.IO,
        jitterMs = { 300L },
        sleep = {},
        network = { look },
        wallClock = { wallNow },
        revokeSilenceMs = 1_500L,
        ownHotspotNow = { hotspotNow },
        vehicles = { cars },
        locationService = service,
    )

    private fun waitFor(what: String, ms: Long = 5_000, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < until) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    @Test fun `forget sends REVOKE before the keys go, and the blocked loop ends without an error`() = runBlocking {
        assertTrue(store.write(key, null))
        val c = controller()
        c.start()
        port.inbox.put(FakeUnit(key).hello(intervalMs = 5000))
        waitFor("the unit in range") { c.status.value.unitInRange }
        val first = port
        c.forget()
        val revoke = first.sent.mapNotNull { Mnai2.openFromPhone(DirectionKeys(key).p2u, it) }.single()
        assertEquals(Mnai2.REVOKE, revoke.type)
        assertEquals(StoredLink.NotPaired, store.load())
        assertEquals(CarLinkNote.FORGET_TELLING, c.status.value.note)
        waitFor("the port closed") { first.closed }
        Thread.sleep(100)
        assertTrue("loop errors: $errors", errors.isEmpty())
    }

    @Test fun `forget with the unit out of range says so and clears every pairing value`() = runBlocking {
        assertTrue(store.write(key, null))
        val c = controller()
        c.start()
        waitFor("the listener") { senders > 0 }
        val first = port
        c.forget()
        assertEquals(CarLinkNote.FORGOT_WILL_TELL, c.status.value.note)
        assertEquals(StoredLink.NotPaired, store.load())
        assertTrue(first.sent.isEmpty())
        waitFor("the port closed") { first.closed }
        Thread.sleep(100)
        assertTrue("loop errors: $errors", errors.isEmpty())
    }

    @Test fun `an unreadable key at start is Broken and nothing is sent`() {
        // A record whose key cannot be read: written normally, then every open fails.
        val file = MemoryRecordFile()
        CarLinkStore(FakeKeystoreStandIn(), file) {}.write(key, null)
        val box = FakeKeystoreStandIn().apply { failOpens = 3 }
        val c = CarLinkController(
            CarLinkStore(box, file) {}, { _, _ -> null },
            HandoverPlan(HandoverSources({ null }, { null }, { null }), UnitTokenNotYetAvailable()) {},
            { _, _, _, _ -> error("a broken pairing must not start a sender") }, scope, Dispatchers.IO, { 300L }, {},
        )
        c.start()
        waitFor("the load") { c.status.value.link != LinkState.LOADING }
        assertEquals(LinkState.BROKEN, c.status.value.link)
        assertTrue(file.values.containsKey("link_current"))
    }

    // ---- P-B: nothing a LAN peer sends, and no surprise in the loop, may crash the app.
    @Test fun `an unexpected exception in the loop is contained and the loop keeps listening`() = runBlocking {
        assertTrue(store.write(key, null))
        val throwing = object : DatagramPort {
            var calls = 0
            override fun receive(buf: ByteArray, timeoutMs: Int): Incoming? {
                calls++
                if (calls == 1) throw IllegalStateException("surprise")
                Thread.sleep(5)
                return null
            }
            override fun send(bytes: ByteArray, to: InetAddress, port: Int) = Unit
            override fun close() = Unit
        }
        val c = CarLinkController(
            store, { _, _ -> null }, HandoverPlan(HandoverSources({ null }, { null }, { null }), UnitTokenNotYetAvailable()) {},
            { ring, onEvent, sendFixes, mode ->
                CarLinkSender(
                    Mnai2Feed(ring, { ByteArray(16) }, lan::contains, { null }, onEvent), throwing,
                    { System.nanoTime() / 1_000_000 },
                    object : FixSource {
                        override fun latest(): FixSample? = null
                        override fun holdFast(on: Boolean) = Unit
                    },
                    sendFixes,
                )
            },
            scope, Dispatchers.IO, { 300L }, {},
        )
        c.start()
        waitFor("the loop to keep receiving") { throwing.calls >= 3 }
        assertTrue("loop errors: $errors", errors.isEmpty())
    }

    // ---- P-C: app start must not block or throw because of the car link.
    @Test fun `the controller reads the store off the caller's thread, and a throwing store is contained`() {
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val callerThread = Thread.currentThread()
        var readOnCaller = false
        val throwingFile = object : RecordFile {
            override fun read(): Map<String, String> {
                reads.incrementAndGet()
                if (Thread.currentThread() === callerThread) readOnCaller = true
                throw IllegalStateException("keystore exploded")
            }
            override fun writeAll(values: Map<String, String?>): Boolean = false
        }
        val c = runCatching { controller(CarLinkStore(FakeKeystoreStandIn(), throwingFile) {}) }
        assertTrue("constructor threw: ${c.exceptionOrNull()}", c.isSuccess)
        runCatching { c.getOrThrow().start() }.onFailure { throw AssertionError("start threw: $it") }
        waitFor("the store to be read off the caller thread") { reads.get() > 0 }
        Thread.sleep(100)
        assertTrue("store read on the caller thread", !readOnCaller)
        assertTrue("loop errors: $errors", errors.isEmpty())
        assertEquals(LinkState.BROKEN, c.getOrThrow().status.value.link)
    }

    // ---- Forget out of range: a REVOKE-only record.
    @Test fun `forget out of range keeps a revoke-only record, sends REVOKE once the unit is back, then wipes it`() =
        runBlocking {
            assertTrue(store.write(key, null))
            val c = controller()
            c.start()
            waitFor("the listener") { senders > 0 }
            c.forget()
            assertEquals(CarLinkNote.FORGOT_WILL_TELL, c.status.value.note)
            assertTrue(store.revokeOnlyPending())
            assertEquals(LinkState.NOT_PAIRED, c.status.value.link)
            waitFor("a listener for the revoke-only record") { senders > 1 }
            port.inbox.put(FakeUnit(key).hello(intervalMs = 1000))
            val p2u = DirectionKeys(key).p2u
            waitFor("REVOKE sent") { port.sent.any { Mnai2.openFromPhone(p2u, it)?.type == Mnai2.REVOKE } }
            val types = port.sent.mapNotNull { Mnai2.openFromPhone(DirectionKeys(key).p2u, it)?.type }
            assertEquals("only a REVOKE, nothing else", listOf(Mnai2.REVOKE), types)
            waitFor("the record wiped") { !store.revokeOnlyPending() }
        }

    @Test fun `a revoke-only record younger than 90 days is listened for, an older one expires unused`() {
        assertTrue(store.writeRevokeOnly(key, wallNow - 89L * DAY))
        controller().start()
        waitFor("a listener for the young record") { senders == 1 }
        assertTrue(store.revokeOnlyPending())

        assertTrue(store.writeRevokeOnly(key, wallNow - 91L * DAY))
        controller().start()
        waitFor("the old record expired") { !store.revokeOnlyPending() }
        Thread.sleep(100)
        assertEquals(1, senders)
    }

    // ---- P-D: nothing listens when the network the phone paired on is absent.
    @Test fun `with the pairing network absent the controller opens no socket`() {
        assertTrue(store.write(key, null))
        look = NetworkLook.Absent
        controller().start()
        Thread.sleep(300)
        assertEquals(0, senders)
    }

    // ---- C1: a pairing pauses the revoke-only listener, and a failed pairing resumes it.
    @Test fun `the revoke-only listener pauses while a pairing runs and resumes if it fails`() = runBlocking {
        assertTrue(store.writeRevokeOnly(key, wallNow))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        transport = com.kevin.legion.carlink.protocol.PairingTransport { _, _ -> gate.await(); null }
        val c = controller()
        c.start()
        waitFor("the revoke listener") { senders == 1 }
        val listening = port
        c.pair(com.kevin.legion.carlink.protocol.CarLinkVectors.qrText())
        waitFor("the listener paused for the pairing") { listening.closed }
        gate.complete(Unit)
        waitFor("the listener back after the failed pairing") { senders == 2 }
        assertTrue(store.revokeOnlyPending())
    }

    // ---- C2: silence counts only after a REVOKE answered a HELLO the listener heard.
    @Test fun `walking away right after an in-range Forget never shows Forgotten`() = runBlocking {
        assertTrue(store.write(key, null))
        val c = controller()
        c.start()
        port.inbox.put(FakeUnit(key).hello(intervalMs = 1000))
        waitFor("the unit in range") { c.status.value.unitInRange }
        c.forget()
        // The REVOKE sent at Forget may be lost; no HELLO follows (the phone walked away).
        // Longer than the silence plus the listener's 5 s idle wait.
        Thread.sleep(7_000)
        assertTrue("the record must wait", store.revokeOnlyPending())
        assertTrue(c.status.value.note != CarLinkNote.FORGOT_REVOKE_SENT)
    }

    // ---- C3: the own-hotspot test runs again at heal time.
    @Test fun `a changed network whose interface is no longer the phone's hotspot is never healed`() {
        assertTrue(store.write(key, null))
        assertTrue(store.setPairingNetwork(PairingNetwork("ap-test0", "10.0.0.0", 24, ownHotspot = true)))
        hotspotNow = false
        val moved = LocalIface("ap-test0", InetAddress.getByName("10.9.9.1") as java.net.Inet4Address, 24)
        look = NetworkLook.Changed(moved)
        val c = controller()
        c.start()
        waitFor("the re-pair words") { c.status.value.note != null }
        assertEquals(CarLinkNote.NETWORK_CHANGED_REPAIR, c.status.value.note)
        Thread.sleep(200)
        assertEquals(0, senders)
    }

    // ---- Screen-off location: the foreground service runs only while paired, switched on, on the network.
    @Test fun `the location service runs only while paired, switched on and on the pairing network`() {
        assertTrue(store.write(key, null))
        val c = controller()
        c.start()
        waitFor("the listener") { senders > 0 }
        Thread.sleep(200)
        assertTrue("not running while the switch is off", !service.running)
        c.setSendLocation(true)
        waitFor("the service started") { service.running }
        c.setSendLocation(false)
        waitFor("the service stopped") { !service.running }
        c.setSendLocation(true)
        waitFor("started again") { service.running }
        look = NetworkLook.Absent
        // The sender notices at its next wake (at most the 5 s idle wait).
        waitFor("stopped when the network left", ms = 12_000) { !service.running }
    }

    // ---- Vehicle choice: the household's cars are offered, and the chosen one is kept for the mint.
    @Test fun `the pairing offers the household's cars and keeps the one chosen`() {
        val c = controller()
        c.start()
        waitFor("the cars") { c.status.value.vehicles.size == 2 }
        c.chooseVehicle("car-b-id")
        waitFor("the choice") { c.status.value.vehicleId == "car-b-id" }
    }

    @Test fun `with one car it is chosen already`() {
        cars = listOf(VehicleOption("only-car-id", "Only car"))
        val c = controller()
        c.start()
        waitFor("the only car chosen") { c.status.value.vehicleId == "only-car-id" }
    }

    private companion object {
        const val DAY = 86_400_000L
    }

    private fun revokes(p: BlockingPort) =
        synchronized(p.sent) { p.sent.toList() }
            .count { Mnai2.openFromPhone(DirectionKeys(key).p2u, it)?.type == Mnai2.REVOKE }

    @Test fun `in-range Forget keeps telling the car on every HELLO until 30 s of silence, then says Forgotten`() =
        runBlocking {
            assertTrue(store.write(key, null))
            val c = controller()
            c.start()
            port.inbox.put(FakeUnit(key).hello(intervalMs = 1000))
            waitFor("the unit in range") { c.status.value.unitInRange }
            val first = port
            c.forget()
            assertEquals(1, revokes(first))
            assertEquals(CarLinkNote.FORGET_TELLING, c.status.value.note)
            assertTrue("kept until the car goes quiet", store.revokeOnlyPending())
            waitFor("the revoke listener") { senders > 1 }
            val unit = FakeUnit(key, session = ByteArray(16) { 0x33 })
            port.inbox.put(unit.hello(intervalMs = 1000))
            waitFor("a REVOKE on that HELLO") { revokes(port) == 1 }
            port.inbox.put(unit.hello(intervalMs = 1000))
            waitFor("a REVOKE again on the next HELLO") { revokes(port) == 2 }
            assertTrue(store.revokeOnlyPending())
            waitFor("wiped after the silence") { !store.revokeOnlyPending() }
            assertEquals(CarLinkNote.FORGOT_REVOKE_SENT, c.status.value.note)
        }

    @Test fun `a revoke-only record past 90 days is not used even if the app has run since`() {
        assertTrue(store.writeRevokeOnly(key, wallNow - 89L * DAY))
        controller().start()
        waitFor("the listener") { senders == 1 }
        wallNow += 2L * DAY
        port.inbox.put(FakeUnit(key).hello(intervalMs = 5000))
        waitFor("the expired record cleared") { !store.revokeOnlyPending() }
        Thread.sleep(200)
        assertEquals(0, revokes(port))
    }

    @Test fun `a taken port is said in words and nothing falls back`() {
        assertTrue(store.write(key, null))
        val c = CarLinkController(
            store, { _, _ -> null }, HandoverPlan(HandoverSources({ null }, { null }, { null }), UnitTokenNotYetAvailable()) {},
            { _, _, _, _ -> throw java.net.BindException("Address already in use") },
            scope, Dispatchers.IO, { 300L }, {},
        )
        c.start()
        waitFor("the words") { c.status.value.note != null }
        assertEquals(CarLinkNote.PORT_TAKEN, c.status.value.note)
        assertTrue("loop errors: $errors", errors.isEmpty())
    }

    @Test fun `a hotspot that came back on a new subnet heals after 2 authenticated HELLOs from a fresh session`() {
        assertTrue(store.write(key, null))
        assertTrue(store.setPairingNetwork(PairingNetwork("ap-test0", "10.0.0.0", 24, ownHotspot = true)))
        val moved = LocalIface("ap-test0", InetAddress.getByName("10.9.9.1") as java.net.Inet4Address, 24)
        look = NetworkLook.Changed(moved)
        val c = controller()
        c.start()
        waitFor("the healing words") { c.status.value.note == CarLinkNote.NETWORK_CHANGED_HEALING }
        waitFor("a listener on the new subnet") { senders > 0 }
        val unit = FakeUnit(key, session = ByteArray(16) { 0x44 })
        port.from = "10.9.9.20"
        port.inbox.put(unit.hello(intervalMs = 5000))
        Thread.sleep(200)
        assertEquals("10.0.0.0", store.pairingNetwork()!!.base)
        port.inbox.put(unit.hello(intervalMs = 5000))
        waitFor("the network re-recorded") { store.pairingNetwork()!!.base == "10.9.9.0" }
        assertTrue(store.pairingNetwork()!!.ownHotspot)
    }

    @Test fun `a client Wi-Fi that changed subnet is never followed, the screen asks for a re-pair`() {
        assertTrue(store.write(key, null))
        assertTrue(store.setPairingNetwork(PairingNetwork("wl-test0", "10.0.0.0", 24, ownHotspot = false)))
        val moved = LocalIface("wl-test0", InetAddress.getByName("10.9.9.7") as java.net.Inet4Address, 24)
        look = NetworkLook.Changed(moved)
        val c = controller()
        c.start()
        waitFor("the words") { c.status.value.note == CarLinkNote.NETWORK_CHANGED_REPAIR }
        Thread.sleep(200)
        assertEquals(0, senders)
    }
}
