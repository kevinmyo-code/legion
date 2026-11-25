package com.kevin.legion.carlink

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What goes over the link in the hand-over (3.5, 5.3 rev 8): the phone's own key and map token. */
class HandoverPlanTest {
    /** Fake engine head-unit routes: obviously invented tokens; records every call. */
    private class FakeUnitTokens : UnitTokenSource {
        val minted = mutableListOf<Pair<String, String?>>()
        val rotated = mutableListOf<String>()
        val revoked = mutableListOf<String>()
        override suspend fun mint(name: String, vehicleId: String?): UnitToken {
            minted += name to vehicleId
            val n = minted.size
            return UnitToken(id = "hu-$n", token = "unit-token-for-tests-$n", expires = "2030-01-01T00:00:00Z")
        }
        override suspend fun rotate(id: String): UnitToken? =
            UnitToken(id, "rotated-for-tests", null).also { rotated += id }
        override suspend fun revoke(id: String): Boolean = revoked.add(id)
    }

    private val tokens = FakeUnitTokens()
    private var phoneKey: String? = null
    private var mapToken: String? = null
    private var spotifyId: String? = null
    private val redacted = mutableListOf<String>()
    private val plan = HandoverPlan(
        HandoverSources({ phoneKey }, { mapToken }, { "Test phone" }, { spotifyId }),
        tokens,
    ) { redacted += it }
    private val allowed = setOf(
        "v", "engine_token", "engine_user_id", "gemini_key", "mapbox_token", "spotify_client_id", "phone_name",
    )

    private fun fields(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test fun `the phone's own Gemini key goes, registered with the redactor`() {
        phoneKey = "phone-gemini-key-for-tests"
        assertEquals("\"phone-gemini-key-for-tests\"", fields(plan.body().toJson())["gemini_key"].toString())
        assertTrue(redacted.contains("phone-gemini-key-for-tests"))
    }

    @Test fun `no Gemini key on the phone sends none and is said`() {
        assertNull(fields(plan.body().toJson())["gemini_key"])
        assertTrue(HandoverGap.NO_GEMINI_KEY in plan.gaps())
        phoneKey = "phone-gemini-key-for-tests"
        assertFalse(HandoverGap.NO_GEMINI_KEY in plan.gaps())
    }

    @Test fun `the phone's map token goes in the map-token field, registered with the redactor`() {
        mapToken = "pk.map-token-for-tests"
        assertEquals("\"pk.map-token-for-tests\"", fields(plan.body().toJson())["mapbox_token"].toString())
        assertTrue(redacted.contains("pk.map-token-for-tests"))
    }

    @Test fun `no map token on the phone sends none and is said`() {
        assertNull(fields(plan.body().toJson())["mapbox_token"])
        assertTrue(HandoverGap.NO_MAP_TOKEN in plan.gaps())
    }

    /*
     * The Spotify client ID (2026-11-23), so the head unit's Spotify setup is not typed in again.
     * Public by design: it is sent as itself, never redacted, and the phone's own Spotify tokens
     * never go at all.
     */

    @Test fun `the phone's Spotify client ID goes over the link`() {
        spotifyId = "made-up-client-id"
        assertEquals("\"made-up-client-id\"", fields(plan.body().toJson())["spotify_client_id"].toString())
        assertFalse(HandoverGap.NO_SPOTIFY_CLIENT_ID in plan.gaps())
        // A client ID identifies an app, so hiding it from this phone's own logs buys nothing.
        assertFalse(redacted.contains("made-up-client-id"))
    }

    @Test fun `no Spotify setup on the phone sends none and is said`() {
        assertNull(fields(plan.body().toJson())["spotify_client_id"])
        assertTrue(HandoverGap.NO_SPOTIFY_CLIENT_ID in plan.gaps())
        spotifyId = "   "
        assertNull(fields(plan.body().toJson())["spotify_client_id"])
        assertTrue(HandoverGap.NO_SPOTIFY_CLIENT_ID in plan.gaps())
    }

    /** Half a client ID is a setup that silently never connects, so an over-long one is dropped. */
    @Test fun `an over-long Spotify client ID is dropped, never cut`() {
        spotifyId = "x".repeat(com.kevin.legion.carlink.protocol.HandoverBody.MAX_SPOTIFY_CLIENT_ID + 1)
        assertNull(fields(plan.body().toJson())["spotify_client_id"])
    }

    @Test fun `the phone's own Spotify tokens are never a field`() {
        spotifyId = "made-up-client-id"
        val json = plan.body().toJson()
        assertFalse(json, "access_token" in json || "refresh_token" in json || "client_secret" in json)
    }

    @Test fun `the unit token is minted for the chosen car, after the unit proved itself`() = runBlocking {
        assertTrue(tokens.minted.isEmpty())
        plan.prepare("vehicle-id-for-tests")
        assertEquals(listOf("Head unit" to "vehicle-id-for-tests"), tokens.minted)
        assertEquals("\"unit-token-for-tests-1\"", fields(plan.body().toJson())["engine_token"].toString())
        assertTrue(redacted.contains("unit-token-for-tests-1"))
    }

    @Test fun `no field can carry a URL or anything the document does not list`() = runBlocking {
        phoneKey = "phone-gemini-key-for-tests"
        mapToken = "pk.map-token-for-tests"
        plan.prepare(null)
        val json = plan.body().toJson()
        assertTrue(allowed.containsAll(fields(json).keys))
        assertFalse(json.contains("http"))
    }

    @Test fun `a failed pairing revokes the token minted for it`() = runBlocking {
        plan.prepare(null)
        plan.discard()
        assertEquals(listOf("hu-1"), tokens.revoked)
        assertNull(plan.mintedId())
    }

    @Test fun `a long phone name is cut to 40 characters`() {
        val long = HandoverPlan(HandoverSources({ null }, { null }, { "x".repeat(60) }), tokens) {}
        assertEquals(40, long.body().phoneName!!.length)
    }

    @Test fun `a source that mints nothing hands over nothing and revokes nothing`() = runBlocking {
        val none = NoUnitTokens()
        assertNull(none.mint("Head unit", null))
        assertNull(none.rotate("hu-1"))
        assertFalse(none.revoke("hu-1"))
    }

    /**
     * The pairing awaits the mint, and the unit drops the stage after 2 minutes while the person is
     * still reading digits off the car screen, so a mint that never answers must cost the token and
     * not the pairing. `runTest` runs on a virtual clock, so this takes no real time.
     */
    @Test fun `a mint that never answers is given up on and the pairing goes on with no token`() =
        kotlinx.coroutines.test.runTest {
            val hangs = object : UnitTokenSource {
                override suspend fun mint(name: String, vehicleId: String?): UnitToken? {
                    kotlinx.coroutines.awaitCancellation()
                }
                override suspend fun rotate(id: String): UnitToken? = null
                override suspend fun revoke(id: String): Boolean = false
            }
            val slow = HandoverPlan(HandoverSources({ null }, { null }, { "Test phone" }), hangs) {}
            slow.prepare("vehicle-id-for-tests")
            assertNull(slow.body().engineToken)
            assertNull(slow.mintedId())
            // The car the person chose is still named, so the hand-over is not silently emptied.
            assertEquals("vehicle-id-for-tests", slow.body().engineVehicleId)
        }

    /**
     * `discard` runs in the pairing's `finally` and the person's next "Pair" tap waits for it, so a
     * revoke the engine never answers must cost the credential and not the retry. `runTest` runs on
     * a virtual clock, so this takes no real time.
     */
    @Test fun `a revoke that never answers is given up on, so the next pairing is not held up`() =
        kotlinx.coroutines.test.runTest {
            val hangs = object : UnitTokenSource {
                override suspend fun mint(name: String, vehicleId: String?): UnitToken =
                    UnitToken("hu-1", "unit-token-for-tests", null)
                override suspend fun rotate(id: String): UnitToken? = null
                override suspend fun revoke(id: String): Boolean = kotlinx.coroutines.awaitCancellation()
            }
            val slow = HandoverPlan(HandoverSources({ null }, { null }, { "Test phone" }), hangs) {}
            slow.prepare(null)
            slow.discard()
            assertNull(slow.mintedId())
            assertNull(slow.body().engineToken)
        }

    @Test fun `the chosen car goes as engine_vehicle_id, and no car means no field`() = runBlocking {
        assertNull(fields(plan.body().toJson())["engine_vehicle_id"])
        plan.prepare("vehicle-id-for-tests")
        assertEquals("\"vehicle-id-for-tests\"", fields(plan.body().toJson())["engine_vehicle_id"].toString())
    }
}
