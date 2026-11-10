package com.kevin.legion.backend.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The `/mcp` request envelope (phone audit 2026-11-10). The engine's MCP SDK refuses a 2026-07-28
 * request whose `params._meta` lacks either envelope key with INVALID_PARAMS, HTTP 400; the phone
 * sent only the protocol version, so every phone call to `/mcp` came back 400. Robolectric for
 * `org.json`, which is stubbed in a plain JVM test.
 */
@RunWith(RobolectricTestRunner::class)
class EngineMcpEnvelopeTest {

    @Test
    fun `every request carries both required envelope keys`() {
        val body = EngineMcpClient.requestBody("tools/list", JSONObject())
        val meta = body.getJSONObject("params").getJSONObject("_meta")
        assertEquals(EngineMcpClient.PROTOCOL_VERSION, meta.getString("io.modelcontextprotocol/protocolVersion"))
        assertTrue(meta.has("io.modelcontextprotocol/clientCapabilities"))
        assertEquals(0, meta.getJSONObject("io.modelcontextprotocol/clientCapabilities").length())
    }

    @Test
    fun `a tool call keeps its own params beside the envelope`() {
        val params = JSONObject().put("name", "list_events").put("arguments", JSONObject().put("days", 3))
        val body = EngineMcpClient.requestBody("tools/call", params)
        assertEquals("2.0", body.getString("jsonrpc"))
        assertEquals("tools/call", body.getString("method"))
        val p = body.getJSONObject("params")
        assertEquals("list_events", p.getString("name"))
        assertEquals(3, p.getJSONObject("arguments").getInt("days"))
        assertTrue(p.getJSONObject("_meta").has("io.modelcontextprotocol/clientCapabilities"))
    }
}
