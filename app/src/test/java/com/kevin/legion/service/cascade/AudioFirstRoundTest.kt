package com.kevin.legion.service.cascade

import com.kevin.legion.service.ChatTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The audio-first round's request (speed plan item 4b, off): his turn as audio, history as text. Robolectric for org.json. */
@RunWith(RobolectricTestRunner::class)
class AudioFirstRoundTest {
    @Test
    fun `his turn carries the note and the audio, the history and the preamble stay as text`() {
        val history = listOf(ChatTurn(fromUser = true, text = "an invented question"), ChatTurn(fromUser = false, text = "an invented answer"))
        val pcm = ByteArray(3_200) { (it % 7).toByte() }
        val c = AudioFirstRound.contents(history, pcm, "(Invented context.)\n\n")
        assertEquals(3, c.length())
        assertTrue(c.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text").startsWith("(Invented context.)"))
        val turn = c.getJSONObject(2)
        assertEquals("user", turn.getString("role"))
        val parts = turn.getJSONArray("parts")
        assertEquals(AudioFirstRound.AUDIO_NOTE, parts.getJSONObject(0).getString("text"))
        val audio = parts.getJSONObject(1).getJSONObject("inlineData")
        assertEquals("audio/wav", audio.getString("mimeType"))
        val wav = CloudSpeechWire.unbase64(audio.getString("data"))!!
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(pcm.size, wav.size - CloudSpeechWire.wavHeaderLength(wav))
    }
}
