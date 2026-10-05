package com.kevin.legion.ui.assistant

import com.kevin.legion.service.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which words go in the narrow pill and which move to the line under the strip. Plain JVM. */
class AssistantStripTypedLayoutTest {

    private fun resolve(
        phase: Phase = Phase.IDLE,
        notice: String? = null,
        mic: Boolean = true,
        silenced: Boolean = false,
    ) = AssistantStripResolver.resolve(phase, "", notice, micGranted = mic, silenced = silenced)

    @Test
    fun `every phase label fits inside the narrow pill`() {
        for (phase in Phase.entries) assertFalse(phase.name, pillIsIconOnly(resolve(phase)))
    }

    @Test
    fun `a notice or a blocked mic moves its words out of the pill`() {
        assertTrue(pillIsIconOnly(resolve(notice = "Didn't start - no Gemini key saved. Add one in Setup")))
        assertTrue(pillIsIconOnly(resolve(mic = false)))
        assertTrue(pillIsIconOnly(resolve(silenced = true)))
    }

    @Test
    fun `mic blocked says typing still works, in words`() {
        val line = stripSubtitle(resolve(mic = false), iconOnly = true)
        assertEquals("Microphone permission needed. Tap to open Settings. Typing still works.", line)
    }

    @Test
    fun `a notice is shown whole under the strip and does not claim the mic is blocked`() {
        val line = stripSubtitle(resolve(notice = "Ended the chat - tap to start another"), iconOnly = true)
        assertEquals("Ended the chat - tap to start another.", line)
    }

    @Test
    fun `without the typed box the subtitle is exactly what it was`() {
        assertNull(stripSubtitle(resolve(), iconOnly = false))
        val listening = AssistantStripResolver.resolve(
            Phase.LISTENING, "go ahead", null, micGranted = true, silenced = false,
        )
        assertEquals("go ahead", stripSubtitle(listening, iconOnly = false))
    }
}
