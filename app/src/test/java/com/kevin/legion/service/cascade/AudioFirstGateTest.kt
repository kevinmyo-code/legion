@file:Suppress("MaxLineLength") // test names and fixtures read best on one line

package com.kevin.legion.service.cascade

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [AudioFirstGate] with short real delays: writes, speech and round 2 wait on the transcript's verdict. Robolectric for org.json. */
@RunWith(RobolectricTestRunner::class)
class AudioFirstGateTest {

    @Test
    fun `a write waits for the checked words and runs with them`() = runBlocking<Unit> {
        val g = AudioFirstGate()
        val write = async { g.awaitForWrite() }
        delay(50)
        assertFalse("no write before the transcript", write.isCompleted)
        g.pass("add milk to the invented list")
        assertEquals("add milk to the invented list", write.await())
        assertEquals("add milk to the invented list", g.awaitForRound())
    }

    @Test
    fun `a transcript that never lands or fails a check lets no write run`() = runBlocking<Unit> {
        val g = AudioFirstGate()
        val write = async { g.awaitForWrite() }
        g.fail()
        assertNull(write.await())
        assertEquals(false, AudioFirstGate.NOT_HEARD.getBoolean("success"))
        assertTrue(AudioFirstGate.NOT_HEARD.getString("message").startsWith("Not done"))
    }

    @Test
    fun `the first audio waits up to the grace, then goes (a grace hit)`() = runBlocking<Unit> {
        val g = AudioFirstGate(graceMs = 60)
        val t0 = System.currentTimeMillis()
        assertTrue("grace hit", g.holdSpeech())
        assertTrue(System.currentTimeMillis() - t0 >= 50)
        val passed = AudioFirstGate(graceMs = 5_000)
        passed.pass("invented words")
        assertFalse("no hit when the words are in", passed.holdSpeech())
    }

    @Test
    fun `a failing verdict inside the grace never lets audio start`() = runBlocking<Unit> {
        val g = AudioFirstGate(graceMs = 5_000)
        g.fail()
        assertNull("holdSpeech must not return", withTimeoutOrNull(200) { g.holdSpeech() })
    }

    @Test
    fun `near silence never reaches the model and the round applies only on the cloud voice with nothing carried`() {
        assertTrue(AudioFirstGate.silent(MissedLine.SILENT_BELOW_MS - 1))
        assertFalse(AudioFirstGate.silent(MissedLine.SILENT_BELOW_MS))
        assertTrue(AudioFirstGate.applies(switchOn = true, cloudVoice = true, carryBlank = true, audioKept = true))
        assertFalse(AudioFirstGate.applies(switchOn = false, cloudVoice = true, carryBlank = true, audioKept = true))
        assertFalse(AudioFirstGate.applies(switchOn = true, cloudVoice = false, carryBlank = true, audioKept = true))
        assertFalse(AudioFirstGate.applies(switchOn = true, cloudVoice = true, carryBlank = false, audioKept = true))
        assertFalse(AudioFirstGate.applies(switchOn = true, cloudVoice = true, carryBlank = true, audioKept = false))
        assertFalse("built off", VoiceEngineSettings.AUDIO_FIRST_DEFAULT)
    }
}
