// Guard clauses: each refusal of a malformed record or address is one early return.
@file:Suppress("ReturnCount")

package com.kevin.legion.carlink

import com.kevin.legion.carlink.protocol.QrPayload
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** What the phone sees of the network it paired on (4.3, rev 6). */
sealed interface NetworkLook {
    object Present : NetworkLook
    object Absent : NetworkLook
    class Changed(val iface: LocalIface) : NetworkLook
}

/** One of this phone's up IPv4 interfaces. */
class LocalIface(val name: String, val address: Inet4Address, val prefix: Int)

/**
 * The network the phone paired on (4.3, rev 5): the name of the interface whose subnet held the
 * unit's address at pairing (the phone's hotspot, or the Wi-Fi it was a client of), and that
 * subnet. The phone listens for and answers HELLOs only while an interface with that name is up with
 * that same subnet, and only from addresses inside it; on any other network (a cafe's Wi-Fi with the
 * same private range included) nothing listens. Not secret.
 */
class PairingNetwork(val name: String, val base: String, val prefix: Int, val ownHotspot: Boolean = false) {
    private val baseBits = bits(InetAddress.getByName(base) as Inet4Address)

    /** True while that interface is up with that subnet. */
    fun present(ifaces: List<LocalIface>): Boolean =
        ifaces.any { it.name == name && it.prefix == prefix && sameSubnet(it.address) }

    /** A private IPv4 address inside the recorded subnet. */
    fun contains(addr: InetAddress): Boolean =
        addr is Inet4Address && QrPayload.isPrivateIpv4(addr.hostAddress ?: "") && sameSubnet(addr)

    /**
     * A HELLO's source counts only inside the recorded subnet and never when it is one of this phone's
     * own addresses (R3: a datagram looped back from the phone itself is not the car).
     */
    fun acceptsHelloFrom(addr: InetAddress, ifaces: List<LocalIface>): Boolean =
        contains(addr) && ifaces.none { it.address == addr }

    /**
     * Present; or Changed when an interface with the recorded NAME is up with a different private
     * subnet (Android may give its hotspot a new range after a toggle, R2); otherwise Absent.
     */
    fun look(ifaces: List<LocalIface>): NetworkLook {
        if (present(ifaces)) return NetworkLook.Present
        val moved = ifaces.firstOrNull {
            it.name == name && it.prefix in MIN_PREFIX..MAX_PREFIX &&
                QrPayload.isPrivateIpv4(it.address.hostAddress ?: "")
        }
        return moved?.let { NetworkLook.Changed(it) } ?: NetworkLook.Absent
    }

    fun encode(): String = "$name$SEP$base$SEP$prefix$SEP${if (ownHotspot) HOTSPOT else CLIENT}"

    private fun sameSubnet(a: Inet4Address): Boolean = (bits(a) and mask(prefix)) == (baseBits and mask(prefix))

    override fun toString() = "PairingNetwork($name/$prefix)"

    companion object {
        private const val SEP = '|'
        private const val MAX_PREFIX = 32
        private const val MIN_PREFIX = 8
        private const val BYTE_BITS = 8
        private const val BYTE_MASK = 0xFF
        private const val PARTS = 3
        private const val HOTSPOT = "hotspot"
        private const val CLIENT = "client"
        private const val OCTETS = 4

        private fun bits(a: Inet4Address) =
            a.address.fold(0) { acc, b -> (acc shl BYTE_BITS) or (b.toInt() and BYTE_MASK) }

        private fun mask(prefix: Int) = if (prefix == 0) 0 else -1 shl (MAX_PREFIX - prefix)

        private fun baseOf(a: Inet4Address, prefix: Int): String {
            val v = bits(a) and mask(prefix)
            return (OCTETS - 1 downTo 0).joinToString(".") { ((v ushr (it * BYTE_BITS)) and BYTE_MASK).toString() }
        }

        /** The interface whose private subnet holds [host] (the unit's address from the QR), or null. */
        /**
         * [isTethered]: the interface is this phone's own hotspot (no Android Network carries it). Only
         * then may a later subnet change be followed (R2).
         */
        fun recordFor(host: String, ifaces: List<LocalIface>, isTethered: (String) -> Boolean): PairingNetwork? {
            if (!QrPayload.isPrivateIpv4(host)) return null
            val h = InetAddress.getByName(host) as Inet4Address
            val iface = ifaces.firstOrNull {
                it.prefix in MIN_PREFIX..MAX_PREFIX && QrPayload.isPrivateIpv4(it.address.hostAddress ?: "") &&
                    (bits(h) and mask(it.prefix)) == (bits(it.address) and mask(it.prefix))
            } ?: return null
            return of(iface, isTethered(iface.name))
        }

        /** The record for [iface] as it is now. */
        fun of(iface: LocalIface, ownHotspot: Boolean) =
            PairingNetwork(iface.name, baseOf(iface.address, iface.prefix), iface.prefix, ownHotspot)

        fun decode(text: String?): PairingNetwork? {
            val p = text?.split(SEP)?.takeIf { it.size == PARTS || it.size == PARTS + 1 } ?: return null
            val prefix = p[2].toIntOrNull()?.takeIf { it in MIN_PREFIX..MAX_PREFIX } ?: return null
            if (!QrPayload.isPrivateIpv4(p[1])) return null
            return PairingNetwork(p[0], p[1], prefix, ownHotspot = p.getOrNull(PARTS) == HOTSPOT)
        }

        /** This phone's up, non-loopback IPv4 interfaces. */
        fun systemInterfaces(): List<LocalIface> =
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { ni ->
                    ni.interfaceAddresses.mapNotNull { ia ->
                        (ia.address as? Inet4Address)?.let { LocalIface(ni.name, it, ia.networkPrefixLength.toInt()) }
                    }
                }
    }
}
