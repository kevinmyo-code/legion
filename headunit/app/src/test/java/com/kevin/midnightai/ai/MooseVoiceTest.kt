package com.kevin.midnightai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moose's voice is persona data, not a fallback default (Kevin, 2026-11-23: the stored voice was
 * wrong and it should be Algenib, as it was before).
 *
 * [CompanionIdentity] is the single source of WHO is speaking, so it owns the voice he speaks in.
 * Gravelly suits a gruff old moose; the retired Zero mascot's Sulafat does not, and it is only ever
 * reached when the stored voice is blank.
 */
class MooseVoiceTest {

    private fun plan(vararg kv: Pair<String, Any>) =
        CompanionProfile.planRetiredVoiceMigration(mapOf(*kv))

    @Test
    fun mooseSpeaksInHisOwnVoice_andEveryDefaultSourcesItFromTheIdentity() {
        assertEquals("Algenib", DEFAULT_VOICE)
        assertEquals("Gravelly", CURATED_VOICES.first { it.name == DEFAULT_VOICE }.descriptor)
        assertTrue("Sulafat is the retired mascot's voice", DEFAULT_VOICE != "Sulafat")
        assertEquals(CompanionIdentity.VOICE, DEFAULT_VOICE)
    }

    @Test
    fun legacyFlatSeededVoice_becomesMoosesVoice_andNeverTouchesTheClock() {
        val w = plan("voice" to "Sulafat", "companion_updated_at" to 10L)
        assertEquals(mapOf<String, Any>("voice" to CompanionIdentity.VOICE), w)
    }

    @Test
    fun perCarSeededVoice_trimmedAndCaseInsensitive_isMigrated() {
        val w = plan("voice:car:abc" to " sulafat ", "voice:AA:BB" to "SULAFAT")
        assertEquals(CompanionIdentity.VOICE, w["voice:car:abc"])
        assertEquals(CompanionIdentity.VOICE, w["voice:AA:BB"])
        assertEquals(2, w.size)
    }

    @Test
    fun aVoiceTheDriverChose_blank_andAlreadyMoose_areUntouched() {
        val w = plan(
            "voice" to "Kore",
            "voice:car:1" to "Gacrux",
            "voice:car:2" to "",
            "voice:car:3" to CompanionIdentity.VOICE,
        )
        assertTrue(w.toString(), w.isEmpty())
    }

    @Test
    fun theDeliveryStyleKeys_otherKeys_andNonStrings_areNotVoiceSlots() {
        val w = plan(
            "name" to "Sulafat",
            "voice_style" to "Sulafat",
            "voice_style_traits" to "Sulafat",
            "voice:car:9" to 7L,
        )
        assertTrue(w.toString(), w.isEmpty())
    }
}
