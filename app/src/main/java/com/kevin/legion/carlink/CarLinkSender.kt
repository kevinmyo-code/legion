package com.kevin.legion.carlink

import com.kevin.legion.carlink.protocol.FixSample
import com.kevin.legion.carlink.protocol.Mnai2
import com.kevin.legion.carlink.protocol.Mnai2Feed
import com.kevin.legion.carlink.protocol.Outgoing
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress

/** A datagram that arrived: its length in the caller's buffer and its source address. */
class Incoming(val length: Int, val from: InetAddress, val port: Int)

/** The UDP port the sender listens and sends on. */
interface DatagramPort : Closeable {
    /** Blocks up to [timeoutMs] for one datagram into [buf]; null on timeout. */
    fun receive(buf: ByteArray, timeoutMs: Int): Incoming?

    fun send(bytes: ByteArray, to: InetAddress, port: Int)
}

/** The phone's own location, and a way to ask for the 1 s cadence only while it is needed. */
interface FixSource {
    fun latest(): FixSample?
    fun holdFast(on: Boolean)
}

/**
 * The location sender's loop body, section 4, foreground-service-friendly: a plain class a service
 * (or any long-lived scope) calls [step] on in a loop; no Android type, so the test drives it with a
 * fake clock and a fake port.
 *
 * Battery: it blocks on the socket instead of polling (a HELLO wakes it), asks for the 1 s GPS
 * cadence only while fixes are on AND the unit is in range, and sends at the interval the unit
 * requests, never faster, and only a fix newer than the last one sent.
 */
class CarLinkSender(
    private val feed: Mnai2Feed,
    private val port: DatagramPort,
    private val clock: () -> Long,
    private val fixes: FixSource,
    private val sendFixes: () -> Boolean,
) {
    private val buf = ByteArray(Mnai2.MAX_DATAGRAM + 1)
    private var fastHeld = false

    /** Datagrams whose handling threw (P-B). */
    var faults = 0
        private set

    fun step() {
        val wait = clock().let { if (feed.inRange(it)) feed.requestedIntervalMs() else IDLE_WAIT_MS }
        val incoming = port.receive(buf, wait.toInt().coerceAtLeast(1))
        val now = clock()
        // An oversized datagram (more than 256 bytes arrived) is not MNAI2: dropped silently.
        if (incoming != null && incoming.length <= Mnai2.MAX_DATAGRAM) {
            send(feed.onDatagram(buf, incoming.length, incoming.from, incoming.port, now))
        }
        val on = sendFixes()
        send(feed.tick(now, if (on) fixes.latest() else null, on))
        val wantFast = on && feed.inRange(now)
        if (wantFast != fastHeld) {
            fixes.holdFast(wantFast)
            fastHeld = wantFast
        }
    }

    fun inRange(): Boolean = feed.inRange(clock())

    /** REVOKE for "Forget head unit" (4.3, 5.4). False = the unit is not in range. */
    fun revoke(): Boolean {
        val out = feed.revoke(clock()) ?: return false
        return send(listOf(out))
    }

    @Synchronized fun close() {
        if (fastHeld) fixes.holdFast(false)
        fastHeld = false
        port.close()
    }

    private fun send(out: List<Outgoing>): Boolean {
        var ok = true
        for (o in out) {
            try {
                port.send(o.bytes, o.to, o.port)
            } catch (_: IOException) {
                ok = false
            }
        }
        return ok
    }

    companion object {
        /** No unit in range: wake at least this often (the unit HELLOs at most every 5 s, 4.3). */
        const val IDLE_WAIT_MS = 5_000L
    }
}

/**
 * "On the unit's LAN": the source is a private IPv4 address inside the subnet of one of this phone's
 * own IPv4 interfaces (its hotspot or its Wi-Fi). The document names the hotspot LAN (sections 1 and
 * 4.6) without defining the check; this is this end's reading of it.
 */
class LocalSubnets(private val interfaces: () -> List<Pair<Inet4Address, Int>>) {
    fun contains(addr: InetAddress): Boolean {
        if (addr !is Inet4Address || !isPrivate(addr)) return false
        val a = toInt(addr)
        return interfaces().any { (local, prefix) ->
            if (prefix !in 1..MAX_PREFIX || !isPrivate(local)) return@any false
            val mask = -1 shl (MAX_PREFIX - prefix)
            (a and mask) == (toInt(local) and mask)
        }
    }

    private fun toInt(a: Inet4Address) =
        a.address.fold(0) { acc, b -> (acc shl BYTE_BITS) or (b.toInt() and BYTE_MASK) }

    private fun isPrivate(a: Inet4Address): Boolean =
        com.kevin.legion.carlink.protocol.QrPayload.isPrivateIpv4(a.hostAddress ?: "")

    companion object {
        private const val MAX_PREFIX = 32
        private const val BYTE_BITS = 8
        private const val BYTE_MASK = 0xFF

        /** This phone's up, non-loopback IPv4 interfaces and their prefix lengths. */
        fun system(): LocalSubnets = LocalSubnets {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { ni ->
                    ni.interfaceAddresses.mapNotNull { ia ->
                        (ia.address as? Inet4Address)?.let { it to ia.networkPrefixLength.toInt() }
                    }
                }
        }
    }
}
