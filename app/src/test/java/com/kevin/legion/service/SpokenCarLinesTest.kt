package com.kevin.legion.service

import com.kevin.legion.service.cascade.VoiceEngineSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SpokenCarLines] and the speaking-speed clamp: the two "felt slow" fixes that are pure. */
class SpokenCarLinesTest {
    @Test
    fun `the AI label is heard once in a window, then not again until it has passed`() {
        SpokenCarLines.resetForTest()
        assertTrue(SpokenCarLines.sayAiLabel(now = 1_000))
        assertFalse(SpokenCarLines.sayAiLabel(now = 1_000 + 60_000))
        assertTrue(SpokenCarLines.sayAiLabel(now = 1_000 + SpokenCarLines.WINDOW_MS))
        SpokenCarLines.resetForTest()
    }

    @Test
    fun `the spoken label is short and still says it is not the standard`() {
        assertTrue(SpokenCarLines.AI_LABEL_SPOKEN.length < com.kevin.legion.vehicle.DtcMeanings.AI_LABEL.length)
        assertTrue(SpokenCarLines.AI_LABEL_SPOKEN.contains("AI"))
        assertFalse(LiveToolbox.ENGINE_CODES_NOT_READ_SPOKEN.contains("("))
    }

    @Test
    fun `speaking speed defaults to 1_15 and stays between 1_0 and 1_4 on 0_05 steps`() {
        assertEquals(1.15f, VoiceEngineSettings.SPEED_DEFAULT, 0f)
        assertEquals(1.0f, VoiceEngineSettings.clampSpeed(0.5f), 0f)
        assertEquals(1.4f, VoiceEngineSettings.clampSpeed(3f), 0f)
        assertEquals(1.25f, VoiceEngineSettings.clampSpeed(1.26f), 0.0001f)
        assertEquals(VoiceEngineSettings.SPEED_DEFAULT, VoiceEngineSettings.clampSpeed(Float.NaN), 0f)
        assertEquals("Speaking speed: 1.15x (applies from the next conversation)", com.kevin.legion.ui.settings.speedLabel(1.15f))
        assertEquals("Speaking speed: 1x (applies from the next conversation)", com.kevin.legion.ui.settings.speedLabel(1.0f))
    }
}
