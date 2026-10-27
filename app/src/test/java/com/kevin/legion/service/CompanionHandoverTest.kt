package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-05, on the phone: with Alfred active, switching companion did not reach TYPED chat - the
 * next typed question was answered by Alfred's session. These tests
 * pin the pure seams [LiveSessionController.companionChanged] and its typed-session open are built
 * on (the controller itself needs a service, a socket and Room, so it cannot be constructed here).
 */
class CompanionHandoverTest {

    @Test
    fun `a switch drops the open session and its resume handle whether or not anyone was talking`() {
        for (talking in listOf(false, true)) {
            val plan = CompanionHandover.plan(inVoiceConversation = talking)
            assertTrue("session is dropped (talking=$talking)", plan.dropSession)
            assertNull("the outgoing thread's handle must not reach the new companion", plan.resumeHandle)
            assertEquals("only a live conversation gets a spoken handover", talking, plan.spokenHandover)
        }
    }

    @Test
    fun `the next typed turn after a switch opens a new session`() {
        // No session left: the typed-turn policy opens one (cold, mic closed).
        val decision = TypedTurnPolicy.decide(
            hasText = true, hasKey = true, online = true,
            shape = CompanionHandover.shapeAfterSwitch, assistantSpeaking = false,
        )
        assertEquals(TypedTurnPolicy.Decision.OpenSession, decision)
    }
}
