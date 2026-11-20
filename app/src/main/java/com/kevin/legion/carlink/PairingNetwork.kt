package com.kevin.legion.carlink

import java.net.Inet4Address
import java.net.InetAddress

/** One of this phone's up IPv4 interfaces. */
class LocalIface(val name: String, val address: Inet4Address, val prefix: Int)

/** STUB (red step). */
class PairingNetwork(val name: String, val base: String, val prefix: Int) {
    fun present(ifaces: List<LocalIface>): Boolean = true

    fun contains(addr: InetAddress): Boolean = true

    companion object {
        fun recordFor(host: String, ifaces: List<LocalIface>): PairingNetwork? = null
    }
}
