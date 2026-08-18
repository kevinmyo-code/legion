package com.kevin.legion.ui.assistant

import com.kevin.legion.service.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pure-logic coverage for [AssistantStripResolver] - the tap-to-talk strip's
 * phase/notice/mic-grant/silenced -> label mapping. No Android dependency, plain JVM
 * test, same shape as [com.kevin.legion.ui.ledger.LedgerEmptyStateResolverTest].
 */
class AssistantStripResolverTest {

    @Test
    fun `missing mic grant wins over every phase`() {
        for (phase in Phase.entries) {
            val state = AssistantStripResolver.resolve(
                phase = phase, caption = "", notice = null, micGranted = false, silenced = false,
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
            silenced = false,
        )
        assertEquals("Microphone permission needed", state.label)
        assertEquals(true, state.micBlocked)
    }

    @Test
    fun `a notice takes the label slot over phase and caption`() {
        val state = AssistantStripResolver.resolve(
            phase = Phase.LISTENING, caption = "hello", notice = "NO SIGNAL OUT HERE", micGranted = true,
            silenced = false,
        )
        assertEquals("NO SIGNAL OUT HERE", state.label)
        assertEquals(null, state.subtitle)
        assertEquals(false, state.micBlocked)
        assertEquals(false, state.active)
    }

    @Test
    fun `idle with a granted mic reads as tap to talk, no subtitle, not active`() {
        val state = AssistantStripResolver.resolve(
            phase = Phase.IDLE, caption = "", notice = null, micGranted = true, silenced = false,
        )
        assertEquals("Tap to talk", state.label)
        assertEquals(null, state.subtitle)
        assertEquals(false, state.active)
    }

    @Test
    fun `connecting and thinking are legible but not active`() {
        assertEquals(false, AssistantStripResolver.resolve(Phase.CONNECTING, "", null, true, false).active)
        assertEquals(false, AssistantStripResolver.resolve(Phase.THINKING, "", null, true, false).active)
        assertEquals("Connecting…", AssistantStripResolver.phaseLabel(Phase.CONNECTING))
        assertEquals("Thinking…", AssistantStripResolver.phaseLabel(Phase.THINKING))
    }

    @Test
    fun `listening and speaking are active and carry the caption as subtitle`() {
        val listening = AssistantStripResolver.resolve(Phase.LISTENING, "go ahead", null, true, false)
        assertEquals("Listening…", listening.label)
        assertEquals("go ahead", listening.subtitle)
        assertEquals(true, listening.active)

        val speaking = AssistantStripResolver.resolve(Phase.SPEAKING, "your oil is due", null, true, false)
        assertEquals("Speaking…", speaking.label)
        assertEquals("your oil is due", speaking.subtitle)
        assertEquals(true, speaking.active)
    }

    @Test
    fun `a blank caption reads as no subtitle, not an empty string`() {
        val state = AssistantStripResolver.resolve(Phase.LISTENING, "   ", null, true, false)
        assertEquals(null, state.subtitle)
    }

    // --- ticket 15: silencing must be said in words -------------------------

    @Test
    fun `a silenced capture never reads as listening`() {
        val state = AssistantStripResolver.resolve(
            phase = Phase.LISTENING, caption = "go ahead", notice = null,
            micGranted = true, silenced = true,
        )
        // The whole point of the ticket: the strip must not claim to be listening
        // while the platform is feeding the capture zeroes.
        assertNotEquals("Listening…", state.label)
        assertEquals("Can't hear you - another app has the microphone", state.label)
        assertEquals(false, state.active)
        assertEquals(true, state.silenced)
        assertEquals(false, state.micBlocked)
    }

    @Test
    fun `silencing wins over every phase and over a notice`() {
        for (phase in Phase.entries) {
            val state = AssistantStripResolver.resolve(
                phase = phase, caption = "hello", notice = "NO SIGNAL OUT HERE",
                micGranted = true, silenced = true,
            )
            assertEquals("Can't hear you - another app has the microphone", state.label)
            assertEquals(true, state.silenced)
        }
    }

    @Test
    fun `a missing mic grant still outranks silencing`() {
        // Both true is reachable: the flag is process-global and can be left set by a
        // session that ran while the grant was still live. The grant is the harder
        // block and the only one a tap can do anything about, so it wins.
        val state = AssistantStripResolver.resolve(
            phase = Phase.LISTENING, caption = "", notice = null,
            micGranted = false, silenced = true,
        )
        assertEquals("Microphone permission needed", state.label)
        assertEquals(true, state.micBlocked)
        assertEquals(false, state.silenced)
    }

    @Test
    fun `not silenced leaves every other state untouched`() {
        val state = AssistantStripResolver.resolve(
            phase = Phase.LISTENING, caption = "go ahead", notice = null,
            micGranted = true, silenced = false,
        )
        assertEquals("Listening…", state.label)
        assertEquals(false, state.silenced)
        assertEquals(true, state.active)
    }
}
