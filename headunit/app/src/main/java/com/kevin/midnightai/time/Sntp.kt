package com.kevin.midnightai.time

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * A minimal SNTP client (RFC 4330), no dependency. The packet build/parse and the offset math are
 * pure and unit-tested; only [query] touches a socket.
 *
 * **The client clock is `SystemClock.elapsedRealtime`, not the wall clock.** T1 (send) and T4
 * (receive) are taken on the monotonic clock, so the offset this computes is `UTC - elapsed`
 * directly, and a wall-clock reset on the head unit (which is the bug this exists for) cannot touch
 * it. The transmit timestamp written into the request is only an anti-spoof token: the server
 * echoes it back as the originate timestamp, and a reply that does not echo it is dropped.
 */
object Sntp {
    const val PORT = 123
    const val PACKET_SIZE = 48
    const val DEFAULT_HOST = "time.google.com"

    /** Seconds from the NTP epoch (1900-01-01) to the Unix epoch (1970-01-01). */
    const val NTP_EPOCH_OFFSET_S = 2_208_988_800L

    private const val TWO_POW_32 = 0x1_0000_0000L
    private const val VERSION = 4
    private const val MODE_CLIENT = 3
    private const val MODE_SERVER = 4
    private const val MODE_BROADCAST = 5
    private const val LEAP_UNSYNCHRONIZED = 3
    private const val ORIGINATE_AT = 24
    private const val RECEIVE_AT = 32
    private const val TRANSMIT_AT = 40

    /** [offsetMs] is server UTC minus the client clock; [roundTripMs] excludes server hold time. */
    data class Result(val offsetMs: Long, val roundTripMs: Long)

    class Rejected(reason: String) : Exception(reason)

    /** A client-mode request whose transmit timestamp is [tokenMs] (echoed back as originate). */
    fun buildRequest(tokenMs: Long): ByteArray {
        val b = ByteArray(PACKET_SIZE)
        b[0] = ((VERSION shl 3) or MODE_CLIENT).toByte() // LI = 0
        writeTimestamp(b, TRANSMIT_AT, tokenMs)
        return b
    }

    /**
     * Validates a reply to [request] and returns the clock offset. [t1] and [t4] are the client
     * clock (elapsedRealtime) just before send and just after receive.
     *
     * @throws Rejected for anything that is not a usable, synchronized server answer to THIS request.
     */
    fun parse(request: ByteArray, response: ByteArray, length: Int, t1: Long, t4: Long): Result {
        if (length < PACKET_SIZE) throw Rejected("short packet ($length bytes)")
        val leap = (response[0].toInt() shr 6) and 0x3
        val mode = response[0].toInt() and 0x7
        val stratum = response[1].toInt() and 0xff
        if (mode != MODE_SERVER && mode != MODE_BROADCAST) throw Rejected("mode $mode is not a server reply")
        if (leap == LEAP_UNSYNCHRONIZED) throw Rejected("server clock unsynchronized")
        if (stratum < 1 || stratum > 15) throw Rejected("stratum $stratum (kiss-o'-death or unsynchronized)")
        for (i in 0 until 8) {
            if (response[ORIGINATE_AT + i] != request[TRANSMIT_AT + i]) throw Rejected("originate does not echo our request")
        }
        val t2 = readTimestamp(response, RECEIVE_AT)
        val t3 = readTimestamp(response, TRANSMIT_AT)
        if (t3 == 0L) throw Rejected("zero transmit timestamp")
        return compute(t1, t2, t3, t4)
    }

    /** The NTP on-wire formula. t1/t4 client clock, t2/t3 server UTC, all in ms. */
    fun compute(t1: Long, t2: Long, t3: Long, t4: Long): Result {
        val offset = ((t2 - t1) + (t3 - t4)) / 2
        val rtt = (t4 - t1) - (t3 - t2)
        return Result(offset, rtt.coerceAtLeast(0))
    }

    fun writeTimestamp(buf: ByteArray, at: Int, unixMs: Long) {
        val seconds = Math.floorDiv(unixMs, 1000L) + NTP_EPOCH_OFFSET_S
        val millis = Math.floorMod(unixMs, 1000L)
        val fraction = millis * TWO_POW_32 / 1000L
        write32(buf, at, seconds)
        write32(buf, at + 4, fraction)
    }

    /** Unix ms, or 0 for an all-zero field. Era-aware: a seconds value with the top bit clear is
     * read as era 1 (after 2036-02-07), the standard SNTP rollover rule. */
    fun readTimestamp(buf: ByteArray, at: Int): Long {
        var seconds = read32(buf, at)
        val fraction = read32(buf, at + 4)
        if (seconds == 0L && fraction == 0L) return 0L
        if ((seconds and 0x8000_0000L) == 0L) seconds += TWO_POW_32
        // Rounded, not floored: the write side floors, so flooring twice would lose a millisecond.
        return (seconds - NTP_EPOCH_OFFSET_S) * 1000L + (fraction * 1000L + TWO_POW_32 / 2) / TWO_POW_32
    }

    private fun write32(buf: ByteArray, at: Int, value: Long) {
        buf[at] = (value shr 24).toByte()
        buf[at + 1] = (value shr 16).toByte()
        buf[at + 2] = (value shr 8).toByte()
        buf[at + 3] = value.toByte()
    }

    private fun read32(buf: ByteArray, at: Int): Long =
        ((buf[at].toLong() and 0xff) shl 24) or
            ((buf[at + 1].toLong() and 0xff) shl 16) or
            ((buf[at + 2].toLong() and 0xff) shl 8) or
            (buf[at + 3].toLong() and 0xff)

    /** One blocking round trip. [clock] is the monotonic client clock; [tokenMs] any value. */
    fun query(host: String, timeoutMs: Int, clock: () -> Long, tokenMs: Long): Result {
        val address = InetAddress.getByName(host)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val request = buildRequest(tokenMs)
            val buffer = ByteArray(PACKET_SIZE * 2)
            val t1 = clock()
            socket.send(DatagramPacket(request, request.size, address, PORT))
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            val t4 = clock()
            return parse(request, buffer, packet.length, t1, t4)
        }
    }
}
