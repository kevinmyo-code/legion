package com.kevin.legion.service

import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the Live `setup` fields added 2026-10-02: VAD sensitivity, explicit
 * context-compression thresholds and affective dialog. The field names and placements are what
 * the server parses; a wrong one closes the socket (proactivity did, on the A25), so they are asserted literally.
 * Runs under Robolectric for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class LiveSetupFlagsTest {

    private fun setup(vadMode: Boolean): JSONObject {
        val session = GeminiLiveSession(ApplicationProvider.getApplicationContext()) {}
        return session.buildSetupJson(
            systemInstruction = "x",
            functionDeclarations = JSONArray(),
            voiceName = "",
            vadMode = vadMode,
            subtitles = false,
            resumeHandle = null,
        ).getJSONObject("setup")
    }

    @Test
    fun `proactivity is never sent - the v1beta server rejects it and closes the socket`() {
        assertFalse(setup(true).has("proactivity"))
    }

    @Test
    fun `affective dialog sits inside generationConfig`() {
        val s = setup(true)
        assertTrue(s.getJSONObject("generationConfig").getBoolean("enableAffectiveDialog"))
        assertFalse(s.has("enableAffectiveDialog"))
    }

    @Test
    fun `vad sensitivity is set only in conversation mode`() {
        val on = setup(true).getJSONObject("realtimeInputConfig")
            .getJSONObject("automaticActivityDetection")
        assertEquals("START_SENSITIVITY_LOW", on.getString("startOfSpeechSensitivity"))
        assertEquals("END_SENSITIVITY_HIGH", on.getString("endOfSpeechSensitivity"))

        val off = setup(false).getJSONObject("realtimeInputConfig")
            .getJSONObject("automaticActivityDetection")
        assertFalse(off.has("startOfSpeechSensitivity"))
        assertFalse(off.has("endOfSpeechSensitivity"))
    }

    @Test
    fun `context compression carries explicit trigger and target`() {
        val c = setup(true).getJSONObject("contextWindowCompression")
        assertEquals(32_000, c.getInt("triggerTokens"))
        assertEquals(16_000, c.getJSONObject("slidingWindow").getInt("targetTokens"))
    }
}
