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
import org.junit.Ignore
import org.junit.Test
import java.net.Inet4Address
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

        override fun receive(buf: ByteArray, timeoutMs: Int): Incoming? {
            if (closed) throw SocketException("closed")
            val d = inbox.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (closed || d === POISON) throw SocketException("closed")
            d ?: return null
            d.copyInto(buf)
            return Incoming(d.size, InetAddress.getByName("10.0.0.1"), 40_000)
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

    private var networkUp = true
    private var wallNow = 1_000_000_000_000L
    private var senders = 0

    private fun controller(store: CarLinkStore = this.store) = CarLinkController(
        store = store,
        transport = { _, _ -> null },
        handover = HandoverPlan({ null }, UnitTokenNotYetAvailable(), { null }, {}),
        newSender = { ring, onEvent, sendFixes, _ ->
            if (senders > 0) port = BlockingPort()
            senders++
            CarLinkSender(
                feed = Mnai2Feed(ring, { ByteArray(16) { 3 } }, lan::contains, { null }, onEvent),
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
        network = { networkUp },
        wallClock = { wallNow },
    )

    private fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
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
        c.forget()
        val revoke = port.sent.mapNotNull { Mnai2.openFromPhone(DirectionKeys(key).p2u, it) }.single()
        assertEquals(Mnai2.REVOKE, revoke.type)
        assertEquals(StoredLink.NotPaired, store.load())
        assertEquals(CarLinkNote.FORGOT_REVOKE_SENT, c.status.value.note)
        waitFor("the port closed") { port.closed }
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
            HandoverPlan({ null }, UnitTokenNotYetAvailable(), { null }, {}),
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
            store, { _, _ -> null }, HandoverPlan({ null }, UnitTokenNotYetAvailable(), { null }, {}),
            { ring, onEvent, sendFixes, _ ->
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
    @Test fun `building and starting the controller never reads the store on the caller, and a throwing store is contained`() {
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
            port.inbox.put(FakeUnit(key).hello(intervalMs = 5000))
            waitFor("REVOKE sent") { port.sent.any { Mnai2.openFromPhone(DirectionKeys(key).p2u, it)?.type == Mnai2.REVOKE } }
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
        networkUp = false
        controller().start()
        Thread.sleep(300)
        assertEquals(0, senders)
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
