package com.kevin.midnightai.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The beacon port is reachable by anything else on the driver's hotspot, so most of
 * these cases are about a bad packet being DROPPED rather than a good one being
 * parsed - a decoder that half-accepts garbage would write a bogus position into
 * [LocationController] and, through it, into the telemetry ledger.
 */
class BeaconProtocolTest {

    private fun ByteArray.decodeFix() = BeaconProtocol.decodeFix(this, size)
    private fun ByteArray.decodeHello() = BeaconProtocol.decodeHello(this, size)

    @Test
    fun `fix round-trips with every field present`() {
        val fix = BeaconFix(
            lat = 42.331429,
            lng = -83.045753,
            accuracyM = 4.5f,
            bearingDeg = 271.25f,
            speedMps = 19.4f,
            altitudeM = 189.3,
        )
        assertEquals(fix, BeaconProtocol.encodeFix(fix).decodeFix())
    }

    @Test
    fun `absent optional fields stay absent rather than becoming zero`() {
        // The distinction matters: a real 0 f bearing means "due north", so a null
        // that decoded as 0 would be a confident wrong heading, not a missing one.
        val fix = BeaconFix(
            lat = 42.331429,
            lng = -83.045753,
            accuracyM = null,
            bearingDeg = null,
            speedMps = null,
            altitudeM = null,
        )
        val decoded = BeaconProtocol.encodeFix(fix).decodeFix()
        assertEquals(fix, decoded)
        assertNull(decoded!!.bearingDeg)
        assertNull(decoded.speedMps)
    }

    @Test
    fun `negative and zero coordinates survive`() {
        val fix = BeaconFix(0.0, 0.0, null, null, null, null)
        assertEquals(fix, BeaconProtocol.encodeFix(fix).decodeFix())
    }

    @Test
    fun `hello round-trips`() {
        assertEquals(1_000L, BeaconProtocol.encodeHello(1_000L).decodeHello())
        assertEquals(30_000L, BeaconProtocol.encodeHello(30_000L).decodeHello())
    }

    @Test
    fun `hello interval is clamped both ways`() {
        // A peer asking for a 1ms cadence would peg the sender; one asking for a day
        // would look identical to a dead link.
        assertEquals(250L, BeaconProtocol.encodeHello(0L).decodeHello())
        assertEquals(60_000L, BeaconProtocol.encodeHello(Long.MAX_VALUE).decodeHello())
    }

    @Test
    fun `wrong magic is rejected`() {
        assertNull("XXXX|F|1.0|2.0||||".toByteArray().let { BeaconProtocol.decodeFix(it, it.size) })
        assertNull("MNAI0|H|1000".toByteArray().let { BeaconProtocol.decodeHello(it, it.size) })
    }

    @Test
    fun `each packet type rejects the other`() {
        assertNull(BeaconProtocol.encodeHello(1_000L).decodeFix())
        assertNull(BeaconProtocol.encodeFix(BeaconFix(1.0, 2.0, null, null, null, null)).decodeHello())
    }

    @Test
    fun `truncated and empty packets are rejected`() {
        assertNull(BeaconProtocol.decodeFix(ByteArray(0), 0))
        assertNull("MNAI1|F|42.3".toByteArray().let { BeaconProtocol.decodeFix(it, it.size) })
        assertNull("MNAI1|F".toByteArray().let { BeaconProtocol.decodeFix(it, it.size) })
    }

    @Test
    fun `non-numeric coordinates are rejected`() {
        assertNull("MNAI1|F|north|west||||".toByteArray().let { BeaconProtocol.decodeFix(it, it.size) })
    }

    @Test
    fun `out-of-range coordinates are rejected`() {
        assertNull("MNAI1|F|91.0|0.0||||".toByteArray().let { BeaconProtocol.decodeFix(it, it.size) })
        assertNull("MNAI1|F|0.0|-181.0||||".toByteArray().let { BeaconProtocol.decodeFix(it, it.size) })
    }

    @Test
    fun `NaN and infinite coordinates are rejected`() {
        assertNull("MNAI1|F|NaN|0.0||||".toByteArray().let { BeaconProtocol.decodeFix(it, it.size) })
        assertNull("MNAI1|F|Infinity|0.0||||".toByteArray().let { BeaconProtocol.decodeFix(it, it.size) })
    }

    @Test
    fun `garbage in an optional field degrades to absent instead of failing the fix`() {
        // Losing a bearing is survivable; losing the whole position because of it
        // would not be, so the packet is still accepted.
        val decoded = "MNAI1|F|42.0|-83.0|4.5|nope||".toByteArray()
            .let { BeaconProtocol.decodeFix(it, it.size) }
        assertNotNull(decoded)
        assertEquals(4.5f, decoded!!.accuracyM)
        assertNull(decoded.bearingDeg)
    }

    @Test
    fun `a length shorter than the buffer decodes only the received bytes`() {
        // Datagram buffers are reused across receives, so trailing bytes from a
        // previous, longer packet are always present and must never be read.
        val payload = BeaconProtocol.encodeFix(BeaconFix(42.0, -83.0, null, null, null, null))
        val buffer = ByteArray(BeaconProtocol.MAX_PACKET_BYTES) { 'Z'.code.toByte() }
        payload.copyInto(buffer)
        val decoded = BeaconProtocol.decodeFix(buffer, payload.size)
        assertEquals(42.0, decoded!!.lat, 0.0)
    }

    @Test
    fun `oversized packets are rejected`() {
        val big = ByteArray(BeaconProtocol.MAX_PACKET_BYTES + 1)
        assertNull(BeaconProtocol.decodeFix(big, big.size))
    }
}
