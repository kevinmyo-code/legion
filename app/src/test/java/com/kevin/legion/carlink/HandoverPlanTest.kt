package com.kevin.legion.carlink

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What goes over the link in the hand-over (3.5): only the fields the document lists, never a URL. */
class HandoverPlanTest {
    /** Fake engine endpoint: mints an obviously invented token and records revocations. */
    private class FakeUnitTokens : UnitTokenSource {
        val minted = mutableListOf<String>()
        val revoked = mutableListOf<String>()
        override suspend fun mint(unitName: String): UnitToken {
            minted += unitName
            val n = minted.size
            return UnitToken(id = "dev-$n", token = "unit-token-for-tests-$n", userId = "u-test")
        }
        override suspend fun revoke(id: String): Boolean = revoked.add(id)
    }

    private val tokens = FakeUnitTokens()
    private var includeGemini = false
    private val redacted = mutableListOf<String>()
    private val plan = HandoverPlan(
        gemini = { "fake-gemini-key-for-tests" },
        tokens = tokens,
        includeGemini = { includeGemini },
        phoneName = { "Test phone" },
        redact = { redacted += it },
    )
    private val allowed = setOf("v", "engine_token", "engine_user_id", "gemini_key", "mapbox_token", "phone_name")

    private fun fields(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test fun `before the unit proves itself no token exists, and the Gemini key stays home by default`() {
        val body = fields(plan.body().toJson())
        assertEquals(setOf("v", "phone_name"), body.keys)
        assertTrue(tokens.minted.isEmpty())
    }

    @Test fun `after prepare the minted unit token goes, registered with the redactor`() = runBlocking {
        plan.prepare()
        val body = fields(plan.body().toJson())
        assertTrue(allowed.containsAll(body.keys))
        assertEquals("\"unit-token-for-tests-1\"", body["engine_token"].toString())
        assertEquals(listOf("Head unit"), tokens.minted)
        assertTrue(redacted.contains("unit-token-for-tests-1"))
    }

    @Test fun `the Gemini key goes only when switched on`() {
        includeGemini = true
        val body = fields(plan.body().toJson())
        assertEquals("\"fake-gemini-key-for-tests\"", body["gemini_key"].toString())
        assertTrue(redacted.contains("fake-gemini-key-for-tests"))
    }

    @Test fun `no field can carry a URL or anything the document does not list`() = runBlocking {
        includeGemini = true
        plan.prepare()
        val json = plan.body().toJson()
        assertTrue(allowed.containsAll(fields(json).keys))
        assertFalse(json.contains("http"))
        assertFalse(json.contains("url", ignoreCase = true))
    }

    @Test fun `a failed pairing revokes the token minted for it`() = runBlocking {
        plan.prepare()
        plan.discard()
        assertEquals(listOf("dev-1"), tokens.revoked)
        assertNull(plan.mintedId())
    }

    @Test fun `a long phone name is cut to 40 characters`() {
        val long = HandoverPlan({ null }, tokens, { false }, { "x".repeat(60) }, {})
        assertEquals(40, long.body().phoneName!!.length)
    }

    @Test fun `production mints nothing until the engine endpoint exists`() = runBlocking {
        assertNull(UnitTokenNotYetAvailable().mint("Head unit"))
    }
}
