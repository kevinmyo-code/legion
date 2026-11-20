package com.kevin.legion.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speech-shaped rules (2026-10-27) live in Alfred's voice-only `delivery`, never in the shared
 * instructions a text-model path could reuse.
 */
class SpeechShapeRulesTest {

    @Test
    fun `speech rules are in the voice delivery`() {
        val d = ALFRED.delivery
        assertTrue(d.contains("no markdown"))
        assertTrue(d.contains("digit by digit"))
        assertTrue(d.contains("one short word of acknowledgement at most"))
        // 2026-11-19: code owns the holding line; length is brief but varied (Kevin: "varied wins").
        assertTrue(d.contains("no holding line before a tool"))
        assertTrue(d.contains("vary sentence length"))
        assertFalse(d.contains("short sentences"))
    }

    @Test
    fun `speech rules stay out of the shared instructions`() {
        assertFalse(SHARED_INSTRUCTIONS.contains("digit by digit"))
        assertFalse(SHARED_INSTRUCTIONS.contains("heard, not read"))
    }
}
