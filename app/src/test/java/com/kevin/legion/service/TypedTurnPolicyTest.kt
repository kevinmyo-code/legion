package com.kevin.legion.service

import com.kevin.legion.service.TypedTurnPolicy.Decision
import com.kevin.legion.service.TypedTurnPolicy.Refusal
import com.kevin.legion.service.TypedTurnPolicy.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlap rules between a typed turn and a spoken one (web-assistant ticket 09). Plain JVM,
 * same shape as [OneShotGateTest]-style pure tests: [TypedTurnPolicy] has no Android dependency.
 */
class TypedTurnPolicyTest {

    private fun decide(
        shape: Shape,
        speaking: Boolean = false,
        hasText: Boolean = true,
        hasKey: Boolean = true,
        online: Boolean = true,
    ) = TypedTurnPolicy.decide(hasText, hasKey, online, shape, speaking)

    @Test
    fun `blank text is ignored, not refused`() {
        for (shape in Shape.entries) assertEquals(Decision.Ignore, decide(shape, hasText = false))
    }

    @Test
    fun `no key says set up Gemini in Setup, in words, for every shape`() {
        for (shape in Shape.entries) {
            val d = decide(shape, hasKey = false) as Decision.Refuse
            assertEquals(Refusal.NO_KEY, d.reason)
        }
        assertEquals(
            "The assistant isn't set up: add a Gemini key in Setup.",
            Refusal.NO_KEY.words,
        )
    }

    @Test
    fun `offline refuses with the reason`() {
        assertEquals(Decision.Refuse(Refusal.OFFLINE), decide(Shape.WARM, online = false))
    }

    @Test
    fun `no session opens one`() {
        assertEquals(Decision.OpenSession, decide(Shape.NO_SESSION))
    }

    @Test
    fun `a prewarm still connecting queues the message`() {
        assertEquals(Decision.QueueUntilConnected, decide(Shape.CONNECTING_IDLE))
    }

    @Test
    fun `a socket connecting for voice refuses rather than racing the greeting`() {
        assertEquals(Decision.Refuse(Refusal.CONNECTING), decide(Shape.CONNECTING_BUSY))
    }

    @Test
    fun `a running tool refuses, because a second user turn into that gap is unsafe`() {
        assertEquals(Decision.Refuse(Refusal.TOOL_RUNNING), decide(Shape.TOOL_RUNNING))
        assertEquals(Decision.Refuse(Refusal.TOOL_RUNNING), decide(Shape.TOOL_RUNNING, speaking = true))
    }

    @Test
    fun `a warm socket sends with the mic closed, and interrupts only when a line is playing`() {
        assertEquals(Decision.SendOnWarm(interrupts = false), decide(Shape.WARM))
        assertEquals(Decision.SendOnWarm(interrupts = true), decide(Shape.WARM, speaking = true))
    }

    @Test
    fun `typing during a voice conversation sends, and interrupts when the assistant is speaking`() {
        assertEquals(Decision.SendInConversation(interrupts = false), decide(Shape.IN_CONVERSATION))
        assertEquals(Decision.SendInConversation(interrupts = true), decide(Shape.IN_CONVERSATION, speaking = true))
    }

    @Test
    fun `a proactive line mid-delivery is replaced by a fresh typed session`() {
        assertEquals(Decision.ReplaceAndOpen, decide(Shape.PROACTIVE_LINE))
    }

    @Test
    fun `no refusal sentence mentions the microphone, because typing needs none`() {
        for (r in Refusal.entries) assertTrue(r.name, !r.words.contains("mic", ignoreCase = true))
    }

    @Test
    fun `every refusal says in words that the message was not sent or the assistant is not set up`() {
        for (r in Refusal.entries) {
            assertTrue(r.name, r.words.startsWith("Not sent") || r == Refusal.NO_KEY)
        }
    }
}
