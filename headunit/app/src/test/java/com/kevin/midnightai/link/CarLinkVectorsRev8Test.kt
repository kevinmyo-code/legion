package com.kevin.midnightai.link

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Protocol rev 8 (3.5): the shared vector `carlink/vectors-rev8.json`, copied byte for byte from the
 * phone branch (git blob c05c550005db05ba0ede2ce9e3e361f953fe3823, made by the phone's
 * make_vectors_rev8.py from the same fixed inputs as vectors.json). The unit opens the sealed
 * hand-over with its own crypto and reads `engine_vehicle_id` and `mapbox_token` with its own
 * parser. Made-up values.
 */
class CarLinkVectorsRev8Test {
    private val v = JSONObject(javaClass.classLoader!!.getResource("carlink/vectors-rev8.json")!!.readText())

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun theUnitOpensTheRev8HandOverAndReadsTheVehicleAndTheMapToken() {
        val opened = LinkCrypto.open(
            hex(v.getString("hand_key")),
            LinkCrypto.nonce(PairingSession.HAND, 1),
            PairingSession.HANDOVER_AAD,
            hex(v.getString("handover_rev8")),
        )
        assertNotNull("the rev 8 hand-over did not open", opened)
        val plain = String(opened!!, Charsets.UTF_8)
        assertEquals(v.getString("handover_rev8_plain"), plain)
        val handover = Handover.parse(JSONObject(plain))!!
        assertEquals("vehicle-id-for-tests", handover.engineVehicleId)
        assertEquals("pk.map-token-for-tests", handover.mapboxToken)
        assertEquals("test-token", handover.engineToken)
        assertEquals("Test phone", handover.phoneName)
    }
}
