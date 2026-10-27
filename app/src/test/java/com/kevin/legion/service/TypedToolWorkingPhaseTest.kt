package com.kevin.legion.service

import com.kevin.legion.ui.dumb.MatrixTalkResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A typed turn running a tool shows "Working..." / Matrix `> WORKING` like voice, but only while
 * typed chat owns the phase (2026-10-27, Kevin: "separate thinking and tool running"). Asserts the
 * pure decisions [LiveSessionController.set] and its typed tool hooks call, as
 * [LiveSessionControllerToolCallRestoreTest] does, since the controller needs a device to build.
 */
class TypedToolWorkingPhaseTest {

    @Test
    fun `a typed tool with voice idle shows working`() {
        // typedPhase put the strip on THINKING; the tool hook may re-publish over it.
        assertTrue(LiveSessionController.typedToolMayRepublish(voiceOwnsPhase = false, currentPhase = Phase.THINKING))
        val running = LiveSessionController.toolRunningFor(0, 1, voiceOwnsPhase = false)
        assertTrue(running)
        val shown = MatrixTalkResolver.resolve(
            Phase.THINKING, null, micGranted = true, silenced = false, toolRunning = running,
        )
        assertEquals("> WORKING", shown.label)
        // Last typed tool done: back to plain thinking.
        assertFalse(LiveSessionController.toolRunningFor(0, 0, voiceOwnsPhase = false))
    }

    @Test
    fun `a typed tool during live voice leaves the voice phase untouched`() {
        for (phase in listOf(Phase.LISTENING, Phase.SPEAKING, Phase.THINKING, Phase.CONNECTING)) {
            assertFalse(
                "no re-publish over voice's $phase",
                LiveSessionController.typedToolMayRepublish(voiceOwnsPhase = true, currentPhase = phase),
            )
        }
        // Voice's own THINKING stays THINKING, not WORKING, while only a typed tool runs.
        assertFalse(LiveSessionController.toolRunningFor(0, 1, voiceOwnsPhase = true))
    }

    @Test
    fun `a voice tool still reads working whatever typed chat is doing`() {
        assertTrue(LiveSessionController.toolRunningFor(1, 0, voiceOwnsPhase = true))
        assertTrue(LiveSessionController.toolRunningFor(1, 1, voiceOwnsPhase = true))
    }

    @Test
    fun `a typed tool never re-publishes over idle`() {
        assertFalse(LiveSessionController.typedToolMayRepublish(voiceOwnsPhase = false, currentPhase = Phase.IDLE))
    }
}
