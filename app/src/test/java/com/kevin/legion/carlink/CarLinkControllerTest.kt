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
            return Incoming(d.size, InetAddress.getByName("10.0.0.1"))
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
    private val port = BlockingPort()
    private val store = CarLinkStore(FakeKeystoreStandIn(), MemoryRecordFile()) {}
    private val lan = LocalSubnets { listOf(InetAddress.getByName("10.0.0.2") as Inet4Address to 24) }

    private fun controller() = CarLinkController(
        store = store,
        transport = { _, _ -> null },
        handover = HandoverPlan({ null }, UnitTokenNotYetAvailable(), { false }, { null }, {}),
        newSender = { ring, onEvent, sendFixes ->
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

    @Test fun `forget with the unit out of range says so and still clears the keys`() = runBlocking {
        assertTrue(store.write(key, null))
        val c = controller()
        c.start()
        c.forget()
        assertEquals(CarLinkNote.FORGOT_NOT_IN_RANGE, c.status.value.note)
        assertEquals(StoredLink.NotPaired, store.load())
        assertTrue(port.sent.isEmpty())
        waitFor("the port closed") { port.closed }
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
            HandoverPlan({ null }, UnitTokenNotYetAvailable(), { false }, { null }, {}),
            { _, _, _ -> error("a broken pairing must not start a sender") }, scope, Dispatchers.IO, { 300L }, {},
        )
        c.start()
        assertEquals(LinkState.BROKEN, c.status.value.link)
        assertTrue(file.values.containsKey("link_current"))
    }
}
