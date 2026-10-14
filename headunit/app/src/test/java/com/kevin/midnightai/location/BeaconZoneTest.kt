package com.kevin.midnightai.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The phone-zone packet (trusted clock, 2026-10-13). */
class BeaconZoneTest {

    private fun String.bytes() = toByteArray(Charsets.US_ASCII)

    @Test
    fun `zone round-trips`() {
        val p = BeaconProtocol.encodeZone("America/Chicago")
        assertEquals("America/Chicago", BeaconProtocol.decodeZone(p, p.size))
        val q = BeaconProtocol.encodeZone("America/Argentina/Buenos_Aires")
        assertEquals("America/Argentina/Buenos_Aires", BeaconProtocol.decodeZone(q, q.size))
    }

    @Test
    fun `an older head unit drops the zone packet instead of misreading it as a fix`() {
        val p = BeaconProtocol.encodeZone("America/Chicago")
        assertNull(BeaconProtocol.decodeFix(p, p.size))
        assertNull(BeaconProtocol.decodeHello(p, p.size))
    }

    @Test
    fun `malformed zone packets are dropped`() {
        listOf(
            "MNAI1|Z|",
            "MNAI1|Z|America/Chicago|extra",
            "MNAI1|Z|America Chicago",
            "MNAI1|Z|America/Chicago;rm",
            "MNAI2|Z|America/Chicago",
            "MNAI1|Z|" + "A".repeat(65),
        ).forEach { raw ->
            val b = raw.bytes()
            assertNull(raw, BeaconProtocol.decodeZone(b, b.size))
        }
        val fix = BeaconProtocol.encodeFix(BeaconFix(1.0, 2.0, null, null, null, null))
        assertNull(BeaconProtocol.decodeZone(fix, fix.size))
    }
}
