package com.kevin.legion.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The codes card is put up from inside ask_fleet's sub-agent (2026-11-03); the session brings
 * the app forward only when the card was shown during that very tool call.
 */
class LiveSessionCodesCardForegroundTest {

    @Test
    fun `a codes card shown during the call brings the app forward`() {
        val p = VoiceModalPayload(VoiceModalTarget.CODES, shownAt = 2_000, vehicleId = "car:jeep")
        assertTrue(modalShownSince(p, sinceMs = 1_000))
    }

    @Test
    fun `an older card, another target, or none does not`() {
        assertFalse(modalShownSince(VoiceModalPayload(VoiceModalTarget.CODES, shownAt = 500), 1_000))
        assertFalse(modalShownSince(VoiceModalPayload(VoiceModalTarget.AGENDA, shownAt = 2_000), 1_000))
        assertFalse(modalShownSince(null, 1_000))
    }
}
