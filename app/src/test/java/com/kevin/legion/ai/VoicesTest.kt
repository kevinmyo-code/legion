package com.kevin.legion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [GeminiVoice.sampleRawName] - the pure resource-name-derivation
 * logic the voice-audition control (`ui/companions/VoiceAudition.kt`) relies
 * on to find a bundled clip via `Resources.getIdentifier`. Plain JUnit, no
 * Android: the identifier lookup itself needs a real `Resources`/instrumented
 * environment and is exercised on-device, not here (see this file's sibling
 * [CompanionProfileStoreTest]'s doc comment for the same JVM-vs-Android split).
 */
class VoicesTest {

    @Test
    fun sampleRawName_lowercasesAndPrefixes() {
        assertEquals("voice_sample_zephyr", GeminiVoice("Zephyr", "Bright").sampleRawName)
        assertEquals("voice_sample_zubenelgenubi", GeminiVoice("Zubenelgenubi", "Casual").sampleRawName)
    }

    @Test
    fun sampleRawName_everyCuratedVoiceMatchesABundledClip() {
        // Guards against CURATED_VOICES drifting from res/raw without anyone
        // noticing - lists every voice_sample_*.wav under res/raw (34 total:
        // Voices.kt's doc comment says 30 curated voices as of 2026-08-02) and
        // asserts every curated voice's derived name is among them. A voice
        // added to CURATED_VOICES without a regenerated clip fails this test
        // loudly instead of silently falling back to VoiceAuditionPlayer's
        // graceful-disable path at runtime.
        val rawDir = java.io.File("src/main/res/raw")
        val bundled = rawDir.listFiles { f -> f.name.startsWith("voice_sample_") && f.name.endsWith(".wav") }
            .orEmpty()
            .map { it.nameWithoutExtension }
            .toSet()
        CURATED_VOICES.forEach { voice ->
            assertTrue(
                "expected a bundled clip for ${voice.name} at res/raw/${voice.sampleRawName}.wav",
                bundled.contains(voice.sampleRawName),
            )
        }
    }
}
