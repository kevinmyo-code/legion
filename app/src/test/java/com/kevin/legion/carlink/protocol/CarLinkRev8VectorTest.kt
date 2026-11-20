package com.kevin.legion.carlink.protocol

import com.kevin.legion.carlink.protocol.CarLinkVectors.hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The revision 8 hand-over vector (`carlink/vectors-rev8.json`, made by `make_vectors_rev8.py` from the
 * document): a plaintext with `engine_vehicle_id` and `mapbox_token`, sealed under the same fixed
 * inputs as `vectors.json`. Both ends assert it.
 */
class CarLinkRev8VectorTest {
    private val v = Json.parseToJsonElement(
        String(javaClass.classLoader!!.getResourceAsStream("carlink/vectors-rev8.json")!!.use { it.readBytes() }),
    ).jsonObject

    private fun str(k: String) = v[k]!!.jsonPrimitive.content

    @Test fun `the phone builds the rev 8 plaintext byte for byte`() {
        val body = HandoverBody(
            engineToken = "test-token",
            engineUserId = "u-1",
            mapboxToken = "pk.map-token-for-tests",
            phoneName = "Test phone",
            engineVehicleId = "vehicle-id-for-tests",
        )
        assertEquals(str("handover_rev8_plain"), body.toJson())
    }

    @Test fun `the rev 8 hand-over seals to the vector's bytes under the vectors' hand key`() {
        val handKey = CarLinkVectors.pairing.hex("hand_key")
        assertEquals(str("hand_key"), CarLinkVectors.hex(handKey))
        val sealed = LinkCrypto.seal(
            handKey, LinkCrypto.nonce("HAND", 1), LinkCrypto.ascii("MNAI-PAIR handover v1"),
            str("handover_rev8_plain").toByteArray(),
        )
        assertEquals(str("handover_rev8"), CarLinkVectors.hex(sealed))
    }
}
