package com.kevin.legion.ui.assistant

import com.kevin.legion.service.Phase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic coverage for [AssistantStripResolver] - the tap-to-talk strip's
 * phase/notice/mic-grant -> label mapping. No Android dependency, plain JVM
 * test, same shape as [com.kevin.legion.ui.ledger.LedgerEmptyStateResolverTest].
 */
class AssistantStripResolverTest {

    @Test
    fun `missing mic grant wins over every phase`() {
        for (phase in Phase.entries) {
            val state = AssistantStripResolver.resolve(
                phase = phase, caption = "", notice = null, micGranted = false,
            )
            assertEquals("Microphone permission needed", state.label)
            assertEquals("Tap to open Settings", state.subtitle)
            assertEquals(true, state.micBlocked)
            assertEquals(false, state.active)
        }
    }

    @Test
    fun `missing mic grant wins even over an active notice`() {
        val state = AssistantStripResolver.resolve(
            phase = Phase.LISTENING, caption = "hello", notice = "ON A CALL", micGranted = false,
        )
        assertEquals("Microphone permission needed", state.label)
        assertEquals(true, state.micBlocked)
    }

    @Test
    fun `a notice takes the label slot over phase and caption`() {
        val state = AssistantStripResolver.resolve(
            phase = Phase.LISTENING, caption = "hello", notice = "NO SIGNAL OUT HERE", micGranted = true,
        )
        assertEquals("NO SIGNAL OUT HERE", state.label)
        assertEquals(null, state.subtitle)
        assertEquals(false, state.micBlocked)
        assertEquals(false, state.active)
    }

    @Test
    fun `idle with a granted mic reads as tap to talk, no subtitle, not active`() {
        val state = AssistantStripResolver.resolve(
            phase = Phase.IDLE, caption = "", notice = null, micGranted = true,
        )
        assertEquals("Tap to talk", state.label)
        assertEquals(null, state.subtitle)
        assertEquals(false, state.active)
    }

    @Test
    fun `connecting and thinking are legible but not active`() {
        assertEquals(false, AssistantStripResolver.resolve(Phase.CONNECTING, "", null, true).active)
        assertEquals(false, AssistantStripResolver.resolve(Phase.THINKING, "", null, true).active)
        assertEquals("Connecting…", AssistantStripResolver.phaseLabel(Phase.CONNECTING))
        assertEquals("Thinking…", AssistantStripResolver.phaseLabel(Phase.THINKING))
    }

    @Test
    fun `listening and speaking are active and carry the caption as subtitle`() {
        val listening = AssistantStripResolver.resolve(Phase.LISTENING, "go ahead", null, true)
        assertEquals("Listening…", listening.label)
        assertEquals("go ahead", listening.subtitle)
        assertEquals(true, listening.active)

        val speaking = AssistantStripResolver.resolve(Phase.SPEAKING, "your oil is due", null, true)
        assertEquals("Speaking…", speaking.label)
        assertEquals("your oil is due", speaking.subtitle)
        assertEquals(true, speaking.active)
    }

    @Test
    fun `a blank caption reads as no subtitle, not an empty string`() {
        val state = AssistantStripResolver.resolve(Phase.LISTENING, "   ", null, true)
        assertEquals(null, state.subtitle)
    }
}
