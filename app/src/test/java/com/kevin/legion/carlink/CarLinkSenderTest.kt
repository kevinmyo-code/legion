package com.kevin.legion.carlink

import com.kevin.legion.carlink.protocol.DirectionKeys
import com.kevin.legion.carlink.protocol.FakeUnit
import com.kevin.legion.carlink.protocol.FixSample
import com.kevin.legion.carlink.protocol.MemoryKeys
import com.kevin.legion.carlink.protocol.Mnai2
import com.kevin.legion.carlink.protocol.Mnai2Feed
import com.kevin.legion.carlink.protocol.PhoneFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** The location sender's loop with a fake clock, a fake UDP port and a fake location source. */
class CarLinkSenderTest {
    private val key = ByteArray(32) { 9 }
    private val unit = FakeUnit(key)
    private val unitAddr = InetAddress.getByName("10.0.0.1")
    private var now = 0L
    private var unitPort = Mnai2.PORT

    private inner class FakePort : DatagramPort {
        val inbox = ArrayDeque<Pair<ByteArray, InetAddress>?>()
        val sent = mutableListOf<Triple<ByteArray, InetAddress, Int>>()
        val waits = mutableListOf<Int>()

        override fun receive(buf: ByteArray, timeoutMs: Int): Incoming? {
            waits += timeoutMs
            val next = inbox.removeFirstOrNull()
            if (next == null) {
                now += timeoutMs
                return null
            }
            // Like DatagramSocket: a datagram longer than the buffer is cut to the buffer's length.
            val n = minOf(next.first.size, buf.size)
            next.first.copyInto(buf, endIndex = n)
            return Incoming(n, next.second, unitPort)
        }

        override fun send(bytes: ByteArray, to: InetAddress, port: Int) { sent += Triple(bytes, to, port) }
        override fun close() = Unit
    }

    private inner class FakeFixes : FixSource {
        var fast = mutableListOf<Boolean>()
        override fun latest() = FixSample(now - 100, PhoneFix(1.5, -2.25, 0))
        override fun holdFast(on: Boolean) { fast += on }
    }

    private val lan = PairingNetwork("ap-test0", "10.0.0.0", 24)
    private val port = FakePort()
    private val fixes = FakeFixes()
    private var setting = true
    private val sender = CarLinkSender(
        feed = Mnai2Feed(MemoryKeys(current = key).ring(), { ByteArray(16) { 1 } }, lan::contains, { null }),
        port = port,
        clock = { now },
        fixes = fixes,
        sendFixes = { setting },
    )

    private fun fixesSent() =
        port.sent.count { Mnai2.openFromPhone(DirectionKeys(key).p2u, it.first)?.type == Mnai2.FIX }

    @Test fun `a HELLO on the LAN starts fixes to the unit on port 17311 and holds the fast cadence`() {
        port.inbox += unit.hello(intervalMs = 1000) to unitAddr
        sender.step()
        val (bytes, to, p) = port.sent.single()
        assertEquals(unitAddr, to)
        assertEquals(Mnai2.PORT, p)
        assertEquals(Mnai2.FIX, Mnai2.openFromPhone(DirectionKeys(key).p2u, bytes)!!.type)
        assertEquals(listOf(true), fixes.fast)
    }

    @Test fun `every datagram goes to the HELLO's source address AND port, never to 17311`() {
        // The unit listens only on the socket its HELLO came from (rev 5, section 4).
        unitPort = 40_001
        port.inbox += unit.hello(intervalMs = 1000) to unitAddr
        sender.step()
        sender.step()
        assertTrue(sender.revoke())
        assertTrue(port.sent.size >= 2)
        assertTrue(port.sent.none { it.third == Mnai2.PORT })
        assertTrue(port.sent.all { it.second == unitAddr && it.third == 40_001 })
    }

    @Test fun `with the setting off nothing is sent and GPS is never asked for`() {
        setting = false
        port.inbox += unit.hello() to unitAddr
        sender.step()
        sender.step()
        assertTrue(port.sent.isEmpty())
        assertTrue(fixes.fast.isEmpty())
    }

    @Test fun `when HELLOs stop the fixes stop and the fast cadence is released`() {
        port.inbox += unit.hello(intervalMs = 1000) to unitAddr
        sender.step()
        repeat(5) { sender.step() }
        val sentWhileAlive = fixesSent()
        repeat(5) { sender.step() }
        assertEquals(sentWhileAlive, fixesSent())
        assertEquals(listOf(true, false), fixes.fast)
    }

    @Test fun `a HELLO from outside this phone's subnet is ignored`() {
        port.inbox += unit.hello() to InetAddress.getByName("10.0.1.5")
        sender.step()
        assertTrue(port.sent.isEmpty())
    }

    @Test fun `idle it blocks on the socket for 5 s, in range for the requested interval`() {
        sender.step()
        assertEquals(5_000, port.waits.last())
        port.inbox += unit.hello(intervalMs = 2000) to unitAddr
        sender.step()
        sender.step()
        assertEquals(2_000, port.waits.last())
    }

    @Test fun `revoke goes out only with a fresh challenge`() {
        assertFalse(sender.revoke())
        port.inbox += unit.hello() to unitAddr
        sender.step()
        assertTrue(sender.revoke())
        assertEquals(Mnai2.REVOKE, Mnai2.openFromPhone(DirectionKeys(key).p2u, port.sent.last().first)!!.type)
    }


    // ---- P-B: any LAN peer can send bytes; handling one must never take the loop down.
    @Test fun `a datagram whose handling throws is counted and contained, and later HELLOs still work`() {
        var explode = true
        val fragile = CarLinkSender(
            feed = Mnai2Feed(
                MemoryKeys(current = key).ring(), { ByteArray(16) { 1 } }, lan::contains,
                { if (explode) error("zone lookup failed") else null },
            ),
            port = port,
            clock = { now },
            fixes = fixes,
            sendFixes = { true },
        )
        port.inbox += unit.hello(intervalMs = 1000) to unitAddr
        fragile.step()
        assertEquals(1, fragile.faults)
        explode = false
        now += 10_000
        port.inbox += unit.hello(intervalMs = 1000) to unitAddr
        fragile.step()
        assertTrue(fragile.inRange())
    }

    @Test fun `random and truncated datagrams never throw out of step`() {
        val r = java.util.Random(11)
        val good = unit.hello()
        repeat(3000) { i ->
            val bytes = when (i % 3) {
                0 -> ByteArray(r.nextInt(300)).also(r::nextBytes)
                1 -> good.copyOf(r.nextInt(good.size))
                else -> good.copyOf().also { it[r.nextInt(it.size)] = r.nextInt().toByte() }
            }
            port.inbox += bytes to unitAddr
            sender.step()
        }
        assertEquals(0, sender.faults)
    }
}
