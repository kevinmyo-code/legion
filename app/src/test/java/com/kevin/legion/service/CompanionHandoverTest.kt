package com.kevin.legion.service

import com.kevin.legion.ai.ALFRED
import com.kevin.legion.ai.MARCUS
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-05, on the phone: with Alfred active, switching to Marcus did not reach TYPED chat - the
 * next typed question was answered by Alfred's session, without `consult_meditations`. These tests
 * pin the pure seams [LiveSessionController.companionChanged] and its typed-session open are built
 * on (the controller itself needs a service, a socket and Room, so it cannot be constructed here).
 */
class CompanionHandoverTest {

    private fun names(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).getString("name") }

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
    fun `the next typed turn after a switch opens a new session with the new persona's declarations`() {
        // No session left: the typed-turn policy opens one (cold, mic closed).
        val decision = TypedTurnPolicy.decide(
            hasText = true, hasKey = true, online = true,
            shape = CompanionHandover.shapeAfterSwitch, assistantSpeaking = false,
        )
        assertEquals(TypedTurnPolicy.Decision.OpenSession, decision)

        // And what that session advertises follows the active persona, typed included.
        val alfred = names(CompanionHandover.declarations(ALFRED.key))
        val marcus = names(CompanionHandover.declarations(MARCUS.key))
        assertFalse("consult_meditations" in alfred)
        assertTrue("consult_meditations" in marcus)
        assertEquals(alfred.toSet() + "consult_meditations", marcus.toSet())
    }
}
