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
        // 2026-10-28: the holding line is varied now, not the one fixed "One moment." (ticket 06).
        assertTrue(d.contains("the short holding line before a slow tool still stands"))
    }

    @Test
    fun `speech rules stay out of the shared instructions`() {
        assertFalse(SHARED_INSTRUCTIONS.contains("digit by digit"))
        assertFalse(SHARED_INSTRUCTIONS.contains("heard, not read"))
    }
}
