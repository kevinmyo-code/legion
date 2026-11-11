package com.kevin.legion.service.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Phase 2: what the recogniser is allowed to hear, and how its result is read. */
@RunWith(RobolectricTestRunner::class)
class LocalCommandGateTest {

    @Test
    fun `vosk's final result becomes text and per-word confidence`() {
        val u = VoskLocalCommandGate.parse(
            """{"result":[{"conf":0.98,"word":"pause"},{"conf":0.91,"word":"music"}],"text":"pause music"}""",
        )
        assertEquals("pause music", u.text)
        assertEquals(listOf(0.98f, 0.91f), u.confidences)
    }

    @Test
    fun `word time spans become coverage of the held speech`() {
        val u = VoskLocalCommandGate.parse(
            """{"result":[{"conf":0.98,"start":0.5,"end":0.9,"word":"pause"}],"text":"pause"}""",
            speechSeconds = 2.0f,
        )
        assertEquals(0.2f, u.coverage()!!, 0.001f)
        assertEquals(1.0f, VoskLocalCommandGate.speechSeconds(ByteArray(64_000)), 0.001f)
    }

    @Test
    fun `decoys are in the grammar the recogniser is given`() {
        assertTrue("cancel" in LocalCommandMatcher.DECOYS)
    }

    @Test
    fun `an empty result is a miss downstream, not a crash`() {
        val u = VoskLocalCommandGate.parse("""{"text":""}""")
        assertEquals("", u.text)
        assertTrue(LocalCommandMatcher().match(u) is LocalMatch.Miss)
    }

    @Test
    fun `every word a hit sentence uses is in the recogniser's grammar`() {
        val sentences = listOf(
            "set a timer for ten minutes", "start a timer for a minute and a half", "timer half an hour",
            "wake me up at six thirty am", "set an alarm for 7 15 pm", "pause the music", "skip this song",
            "go back a song", "turn the volume down", "set the volume to forty", "that will be all",
            "go to sleep", "stand down", "never mind", "show me my calendar", "open the ledger",
            "clear the ticked items on groceries", "clear the ticked ones from my to do list",
        )
        for (s in sentences) {
            for (w in LocalCommandMatcher.tokens(s).filter { w -> w.any(Char::isLetter) }) {
                assertTrue("\"$w\" (from \"$s\") is not in the grammar", w in LocalCommandMatcher.VOCABULARY)
            }
        }
    }
}
