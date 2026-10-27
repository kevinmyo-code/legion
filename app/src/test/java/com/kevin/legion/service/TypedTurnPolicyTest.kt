package com.kevin.legion.service

import com.kevin.legion.service.TypedTurnPolicy.Decision
import com.kevin.legion.service.TypedTurnPolicy.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Typed chat's routing rules (Kevin, 2026-10-27: typed chat goes to a text model). Plain JVM:
 * [TypedTurnPolicy] has no Android dependency. The voice session's state is deliberately not an
 * input any more, which is what these tests pin by having no way to pass one.
 */
class TypedTurnPolicyTest {

    private fun decide(
        hasText: Boolean = true,
        crisis: Boolean = false,
        hasKey: Boolean = true,
        online: Boolean = true,
        busy: Boolean = false,
    ) = TypedTurnPolicy.decide(hasText, crisis, hasKey, online, busy)

    @Test
    fun `blank text is ignored, not refused`() {
        assertEquals(Decision.Ignore, decide(hasText = false))
        assertEquals(Decision.Ignore, decide(hasText = false, crisis = true, hasKey = false, online = false))
    }

    @Test
    fun `an ordinary message is sent to the text model`() {
        assertEquals(Decision.Send, decide())
    }

    @Test
    fun `a crisis phrase wins over everything, even no key, no connection or a busy turn`() {
        assertEquals(Decision.Crisis, decide(crisis = true))
        assertEquals(Decision.Crisis, decide(crisis = true, hasKey = false, online = false, busy = true))
    }

    @Test
    fun `no key says set up Gemini in Setup, in words`() {
        assertEquals(Decision.Refuse(Refusal.NO_KEY), decide(hasKey = false))
        assertEquals("The assistant isn't set up: add a Gemini key in Setup.", Refusal.NO_KEY.words)
    }

    @Test
    fun `offline refuses with the reason`() {
        assertEquals(Decision.Refuse(Refusal.OFFLINE), decide(online = false))
    }

    @Test
    fun `a second message while one is being answered is not sent, and says so`() {
        assertEquals(Decision.Refuse(Refusal.BUSY), decide(busy = true))
        assertEquals(
            "Not sent: still answering your last message. Try again when it finishes.",
            Refusal.BUSY.words,
        )
    }

    @Test
    fun `no refusal mentions the microphone - typing needs none`() {
        for (r in Refusal.entries) assertFalse(r.words, r.words.contains("mic", ignoreCase = true))
    }
}
