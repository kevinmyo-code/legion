package com.kevin.legion.service.phonelog

import com.kevin.legion.service.LiveToolbox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a logcat line may carry to the engine (2026-11-09). Each case is a line shaped like one the
 * app or a library actually writes; the header is always kept byte for byte.
 */
class LogcatRedactorTest {
    private val head = "11-09 14:03:22.123  4242  4300 I SomeTag : "

    @After fun tearDown() = LogcatRedactor.clearLiterals()

    private fun r(message: String, tag: String = "SomeTag") =
        LogcatRedactor.redactLine("11-09 14:03:22.123  4242  4300 I $tag : $message")
            .removePrefix("11-09 14:03:22.123  4242  4300 I $tag : ")

    @Test fun `the header is never touched, even when it looks like a phone number`() {
        val line = "11-09 14:03:22.123  7135551234  4300 I Tag: plain words"
        assertEquals(line, LogcatRedactor.redactLine(line))
    }

    @Test fun `authorization headers lose their value`() {
        assertEquals("Authorization: [redacted]", r("Authorization: Token 0123456789abcdef0123"))
        assertEquals("authorization=[redacted]", r("authorization=Bearer abc.def.ghi"))
        assertFalse(r("--> Authorization: Bearer ya29.a0AfB_byCzX12345678901234567890").contains("ya29"))
    }

    @Test fun `cookies lose their value`() {
        assertEquals("Cookie: [redacted]", r("Cookie: sessionid=abc123; csrftoken=zz"))
        assertEquals("Set-Cookie: [redacted]", r("Set-Cookie: sessionid=abc123; Path=/"))
    }

    @Test fun `keys tokens and secrets in urls and json`() {
        assertEquals(
            "GET https://x.googleapis.com/v1?key=[redacted]&alt=json",
            r("GET https://x.googleapis.com/v1?key=AIzaSyA1234567890abcdefghijklmnop&alt=json"),
        )
        assertEquals("{\"access_token\":\"[redacted]\",\"ok\":true}", r("{\"access_token\":\"abc\",\"ok\":true}"))
        assertEquals("refresh_token=[redacted] next", r("refresh_token=xyz123 next"))
        assertFalse(r("key AIzaSyA1234567890abcdefghijklmnop leaked").contains("AIza"))
    }

    @Test fun `a jwt and long opaque runs`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        assertEquals("session [redacted] ok", r("session $jwt ok"))
        assertEquals("id [redacted]", r("id 0123456789abcdef0123456789abcdef01"))
    }

    @Test fun `ordinary words and short tokens survive`() {
        val msg = "Token refreshed in 120 ms; engine_poll[events] inserted=0 updated=3 key=ok"
        assertTrue(r(msg).startsWith("Token refreshed in 120 ms; engine_poll[events] inserted=0 updated=3"))
    }

    @Test fun `registered literals are stripped anywhere`() {
        LogcatRedactor.addLiteral("s3cr3t-engine-token")
        LogcatRedactor.addLiteral("short")
        assertEquals("sent [redacted] and short", r("sent s3cr3t-engine-token and short"))
    }

    @Test fun `email addresses become a marker`() {
        assertEquals("from [email] to [email]", r("from alice.smith+x@example.com to bob@mail.co.uk"))
    }

    @Test fun `phone numbers in their common shapes`() {
        assertEquals("call [phone] now", r("call (713) 555-1234 now"))
        assertEquals("call [phone]", r("call 713-555-1234"))
        assertEquals("call [phone]", r("call +1 713 555 1234"))
        assertEquals("sg [phone]", r("sg +6591234567"))
        assertEquals("dial tel:[phone]", r("dial tel:+17135551234"))
        assertEquals("phone=[phone] label=Mom", r("phone=7135551234 label=Mom"))
        assertEquals("\"number\": [phone]", r("\"number\": 713 555 1234"))
    }

    @Test fun `a contact label survives while its number goes`() {
        assertEquals("place_call contact=Mom number=[phone]", r("place_call contact=Mom number=+1 (713) 555-1234"))
    }

    @Test fun `epoch milliseconds row ids and versions are not phone numbers`() {
        val msg = "window_start_ms=1762718400000 row=7135551234 v=1.2.3 at 14:03:22.123 mac=66:1E:11:0E:82:0E"
        assertEquals(msg, r(msg))
    }

    @Test fun `an ssn shape is redacted`() {
        assertEquals("id [id] seen", r("id 123-45-6789 seen"))
    }

    @Test fun `a read-through tool keeps its name and loses its payload, as LiveAudit does`() {
        val line = "tool_result id=t1 name=ask_mail success=true ms=900 scheduling=none sent=true " +
            "response={\"answer\":\"Your landlord said the rent is due\"}"
        assertEquals(
            "tool_result id=t1 name=ask_mail success=true ms=900 scheduling=none sent=true " +
                "response=\"${LogcatRedactor.THIRD_PARTY}\"",
            r(line, tag = "LIVE_AUDIT"),
        )
    }

    @Test fun `a read-through tool's args stay, as LiveAudit keeps them`() {
        val line = "tool_call id=t1 name=ask_mail async=true args={\"question\":\"did rent go out\"}"
        assertEquals(line, r(line, tag = "LIVE_AUDIT"))
    }

    @Test fun `any other tool's response stays`() {
        val line = "tool_result id=t2 name=get_weather success=true response={\"temp\":20}"
        assertEquals(line, r(line, tag = "LIVE_AUDIT"))
    }

    @Test fun `a word that only contains a tool name does not trigger it`() {
        val line = "webview_browser_cache response=ok"
        assertEquals(line, r(line))
    }

    @Test fun `gmail client lines lose search queries and parse payloads`() {
        assertEquals(
            "GET https://gmail.googleapis.com/gmail/v1/users/me/messages?[redacted] failed: 500",
            r(
                "GET https://gmail.googleapis.com/gmail/v1/users/me/messages?q=from%3Alandlord%20rent&maxResults=5 " +
                    "failed: 500",
                "GmailClient",
            ),
        )
        assertEquals(
            "fetchFull m1: unparseable response: ${LogcatRedactor.THIRD_PARTY}",
            r("fetchFull m1: unparseable response: Value Hi Kevin, the rent of type String", "GmailClient"),
        )
    }

    @Test fun `mail json fields are blanked wherever they appear`() {
        val out = r(
            "{\"id\":\"m1\",\"subject\":\"Rent due\",\"snippet\":\"Hi \\\"Kevin\\\"\",\"labelIds\":[\"INBOX\"]}",
        )
        assertEquals(
            "{\"id\":\"m1\",\"subject\":\"${LogcatRedactor.THIRD_PARTY}\"," +
                "\"snippet\":\"${LogcatRedactor.THIRD_PARTY}\",\"labelIds\":[\"INBOX\"]}",
            out,
        )
    }

    @Test fun `a calendar range keeps its from and to`() {
        val line = "tool_call id=t3 name=get_calendar args={\"from\":\"2026-11-09\",\"to\":\"2026-11-10\"}"
        assertEquals(line, r(line, tag = "LIVE_AUDIT"))
    }

    @Test fun `a line with no threadtime header is still redacted`() {
        val bare = LogcatRedactor.redactLine("Authorization: Token abcdefabcdef12345678")
        assertEquals("Authorization: [redacted]", bare)
    }

    @Test fun `redacting twice changes nothing more`() {
        val once = LogcatRedactor.redactLine(
            head + "Authorization: Token abcdefabcdef12345678 to a@b.com (713) 555-1234",
        )
        assertEquals(once, LogcatRedactor.redactLine(once))
    }

    @Test fun `the read-through set is LiveToolbox's own`() {
        assertEquals(LiveToolbox.EPISODIC_EXCLUDED_TOOLS, LogcatRedactor.READ_THROUGH_TOOLS)
    }
}
