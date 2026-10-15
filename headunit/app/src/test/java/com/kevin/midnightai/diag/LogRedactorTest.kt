package com.kevin.midnightai.diag

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {

    @After
    fun reset() = LogRedactor.clearLiterals()

    private fun assertGone(secret: String, line: String) {
        val out = LogRedactor.redact(line)
        assertFalse("secret survived: $out", out.contains(secret))
        assertTrue("marker missing: $out", out.contains(LogRedactor.MARK))
    }

    @Test
    fun `the engine Authorization Token header is redacted`() {
        val key = "3f9c1d0a7b6e4c2f8a5d9e1b0c7a6f4e2d8b1c3a"
        assertGone(key, "D OkHttp: Authorization: Token $key")
    }

    @Test
    fun `a Bearer token is redacted`() {
        assertGone("abcdEFGH1234ijklMNOP", "I Net: Authorization=Bearer abcdEFGH1234ijklMNOP")
        assertGone("abcdEFGH1234ijklMNOP", "I Net: sending Bearer abcdEFGH1234ijklMNOP now")
    }

    @Test
    fun `key equals in a URL is redacted but the rest of the URL stays`() {
        val out = LogRedactor.redact("wss://generativelanguage.googleapis.com/ws?key=AIzaSyA-1234567890abcdefghijklmnopqrstu&x=1")
        assertFalse(out.contains("AIza"))
        assertTrue(out.startsWith("wss://generativelanguage.googleapis.com/ws?key="))
        assertTrue(out.endsWith("&x=1"))
    }

    @Test
    fun `a bare Gemini key is redacted by shape`() {
        assertGone("AIzaSyA1234567890abcdefghijklmnopqrstu", "key was AIzaSyA1234567890abcdefghijklmnopqrstu ok")
    }

    @Test
    fun `a spotify client id in a form body or json is redacted`() {
        val id = "0123456789abcdef0123456789abcdef"
        assertGone(id, "POST body client_id=$id&grant_type=refresh_token")
        assertGone(id, """{"client_id":"$id","scope":"x"}""")
        assertGone(id, "found $id in prefs")
    }

    @Test
    fun `a JWT is redacted`() {
        assertGone(
            "eyJhbGciOiJIUzI1NiJ9",
            "tok eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk",
        )
    }

    @Test
    fun `a long mixed base64 run is redacted`() {
        assertGone("Zm9vYmFyMTIzNDU2Nzg5MGFiY2RlZmdoaWprbG1ub3BxcnN0", "blob Zm9vYmFyMTIzNDU2Nzg5MGFiY2RlZmdoaWprbG1ub3BxcnN0 end")
    }

    @Test
    fun `a registered literal is removed wherever it appears`() {
        LogRedactor.addLiteral("hunter2-hunter2")
        assertGone("hunter2-hunter2", "weird form: hunter2-hunter2.")
    }

    @Test
    fun `ordinary log lines pass through untouched`() {
        val lines = listOf(
            "10-15 08:00:00.123  1234  1250 I MidnightApp: obd_connected: 00:1D:A5:68:98:8B",
            "10-15 08:00:01.000  1234  1250 D SpotifyController: connect failed after 3 tries",
            "I Breadcrumb: tool_dispatched: ask_engine",
            "W Token refreshed ok",
            "E File: /data/user/0/com.kevin.midnightai/files/drivelog/current.log",
            "I com.kevin.midnightai.service.LiveSessionController.ENGINE_TOOL_TIMEOUT_MS reached",
        )
        lines.forEach { assertEquals(it, LogRedactor.redact(it)) }
    }
}
