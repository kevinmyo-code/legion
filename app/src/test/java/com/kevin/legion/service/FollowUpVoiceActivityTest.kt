package com.kevin.legion.service

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [FollowUpWindow.isActivityStart] against the root messages the A25 spike captured (2026-10-27). */
class FollowUpVoiceActivityTest {

    @Test
    fun `activity start is user speech`() {
        assertTrue(
            FollowUpWindow.isActivityStart(
                JSONObject("""{"voiceActivity":{"type":"ACTIVITY_START","audioOffset":"1.240s"}}"""),
            ),
        )
    }

    @Test
    fun `activity end and other messages are not`() {
        assertFalse(FollowUpWindow.isActivityStart(JSONObject("""{"voiceActivity":{"type":"ACTIVITY_END"}}""")))
        assertFalse(FollowUpWindow.isActivityStart(JSONObject("""{"serverContent":{"turnComplete":true}}""")))
        assertFalse(FollowUpWindow.isActivityStart(JSONObject("""{"voiceActivity":{}}""")))
    }

    @Test
    fun `the local speech threshold sits between the measured room and voice`() {
        // A25, 2026-10-27: room peaked 16-31, Kevin's speech 200-400.
        assertTrue(FollowUpWindow.LOCAL_SPEECH_PEAK in 32..199)
    }
}
