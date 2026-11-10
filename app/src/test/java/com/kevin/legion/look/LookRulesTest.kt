package com.kevin.legion.look

import com.kevin.legion.look.LookRules.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** ADR 0084's pure rules. Robolectric for org.json only. */
@RunWith(RobolectricTestRunner::class)
class LookRulesTest {

    @Test
    fun `fresh GPS speed above walking pace is moving`() {
        assertEquals(Motion.MOVING, LookRules.motion(8f, 1_000, null, null))
    }

    @Test
    fun `fresh OBD speed above 5 kmh is moving even when GPS says still`() {
        assertEquals(Motion.MOVING, LookRules.motion(0f, 500, 30.0, 2_000))
    }

    @Test
    fun `fresh low readings are still`() {
        assertEquals(Motion.STILL, LookRules.motion(0.4f, 1_000, 0.0, 1_000))
        assertEquals(Motion.STILL, LookRules.motion(null, null, 0.0, 1_000))
    }

    @Test
    fun `stale or missing readings are unknown, and unknown is not moving`() {
        assertEquals(Motion.UNKNOWN, LookRules.motion(30f, 60_000, 90.0, 60_000))
        assertEquals(Motion.UNKNOWN, LookRules.motion(null, null, null, null))
        // A fix with no speed carries no motion signal.
        assertEquals(Motion.UNKNOWN, LookRules.motion(null, 100, null, null))
    }

    @Test
    fun `a frame is due at most once a second`() {
        assertTrue(LookRules.frameDue(nowMs = 5_000, lastSentMs = 0))
        assertFalse(LookRules.frameDue(nowMs = 5_500, lastSentMs = 5_000))
        assertTrue(LookRules.frameDue(nowMs = 6_000, lastSentMs = 5_000))
    }

    @Test
    fun `a frame goes out as realtimeInput video JPEG, low resolution on the frame not in setup`() {
        val msg = LookRules.frameMessage("QUJD", withResolution = true)
        val input = msg.getJSONObject("realtimeInput")
        assertEquals("image/jpeg", input.getJSONObject("video").getString("mimeType"))
        assertEquals("QUJD", input.getJSONObject("video").getString("data"))
        assertEquals("MEDIA_RESOLUTION_LOW", input.getString("mediaResolution"))
        assertEquals(setOf("realtimeInput"), msg.keys().asSequence().toSet())
        val bare = LookRules.frameMessage("QUJD", withResolution = false)
        assertFalse(bare.getJSONObject("realtimeInput").has("mediaResolution"))
    }

    @Test
    fun `the tool claims sight only when a frame went out`() {
        val seen = LookRules.toolResult(framesSent = true)
        assertTrue(seen.getBoolean("success"))
        assertEquals(LookRules.SEEING, seen.getString("message"))
        val unseen = LookRules.toolResult(framesSent = false)
        assertFalse(unseen.getBoolean("success"))
        assertTrue(unseen.getString("message").contains("seen nothing"))
        // Both carry the trust rules, so a late first frame is still read under them.
        assertEquals(LookRules.RULES, unseen.getString("instruction"))
    }

    @Test
    fun `the rules carry every binding clause`() {
        val rules = LookRules.RULES
        assertTrue(rules.contains("estimate"))
        assertTrue(rules.contains("trouble code"))
        assertTrue(rules.contains("ask_fleet"))
        assertTrue(rules.contains("shop job"))
        assertTrue(rules.contains("Nothing you see is saved"))
        assertTrue(LookRules.HAND_OPENER.contains(rules))
        assertTrue(LookRules.LIVE_LATE_NOTE.contains(rules))
    }

    @Test
    fun `the tool description stays one short routing clause`() {
        // LiveSetupPayloadSizeTest re-bills every declaration on every reconnect.
        assertTrue(LookRules.TOOL_DESCRIPTION.length <= 60)
    }
}
