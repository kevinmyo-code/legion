package com.kevin.midnightai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moose's voice is persona data, not a fallback default (Kevin, 2026-11-23: "moose voice is like a
 * woman's voice which is wrong. it was algenib before").
 *
 * [CompanionIdentity] is the single source of WHO is speaking, so it owns the voice he speaks in.
 * Gravelly suits a gruff old moose; the retired Zero mascot's warm, female Sulafat does not, and it
 * is only ever reached when the stored voice is blank.
 */
class MooseVoiceTest {

    @Test
    fun mooseSpeaksInHisOwnVoice_andEveryDefaultSourcesItFromTheIdentity() {
        assertEquals("Algenib", DEFAULT_VOICE)
        assertEquals("Gravelly", CURATED_VOICES.first { it.name == DEFAULT_VOICE }.descriptor)
        assertTrue("Sulafat is the retired mascot's voice", DEFAULT_VOICE != "Sulafat")
    }
}
