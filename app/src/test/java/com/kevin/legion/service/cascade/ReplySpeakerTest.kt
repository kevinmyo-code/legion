package com.kevin.legion.service.cascade

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** [ReplySpeaker] against a fake voice: order, streaming, first-audio deadline, failure, interrupt. */
class ReplySpeakerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A fake voice. Each clip sends one frame per word (the word's bytes) and then completes. */
    private class FakeVoice(
        override val streamsText: Boolean = false,
        val silentFor: Set<String> = emptySet(),
        val failFor: Set<String> = emptySet(),
        val refuseStream: Boolean = false,
        val frameDelayMs: Long = 0,
    ) : TextToSpeech {
        override val sampleRate = 24_000
        val asked = Collections.synchronizedList(mutableListOf<String>())
        private var nextId = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        inner class Clip(override val id: Int, initial: String, val streamed: Boolean) : StreamingSpeechClip {
            override val frames = Channel<ByteArray>(Channel.UNLIMITED)
            private val done = CompletableDeferred<TtsOutcome>()
            private val sb = StringBuffer(initial)
            override val text: String get() = sb.toString()

            fun produce() = scope.launch {
                val t = text.trim()
                if (t in failFor) {
                    done.complete(TtsOutcome.Failed("internal"))
                    frames.close()
                    return@launch
                }
                if (t in silentFor) return@launch // never answers: the first-audio deadline must catch it
                for (w in t.split(" ")) {
                    if (done.isCompleted) break
                    delay(frameDelayMs)
                    frames.trySend(w.toByteArray())
                }
                if (done.complete(TtsOutcome.Done(10, 5))) frames.close()
            }

            override fun append(text: String) {
                sb.append(text)
            }

            override fun finish() {
                if (refuseStream) {
                    done.complete(TtsOutcome.Failed("bad_request"))
                    frames.close()
                } else {
                    produce()
                }
            }

            override fun cancel() {
                if (done.complete(TtsOutcome.Cancelled)) frames.close()
            }

            override suspend fun outcome() = done.await()
        }

        override fun speak(text: String): SpeechClip {
            asked.add(text)
            return Clip(nextId++, text, false).also { it.produce() }
        }

        override fun openStream(): StreamingSpeechClip {
            asked.add("<stream>")
            return Clip(nextId++, "", true)
        }
    }

    private class Ear {
        val heard = Collections.synchronizedList(mutableListOf<String>())
        var firstAudio = 0
        suspend fun play(pcm: ByteArray): Boolean {
            heard.add(String(pcm))
            return true
        }
    }

    private fun speaker(voice: FakeVoice, ear: Ear, timeoutMs: Long = 500) = ReplySpeaker(
        tts = voice,
        scope = scope,
        play = { ear.play(it) },
        firstFrameTimeoutMs = timeoutMs,
        onFirstAudio = { ear.firstAudio++ },
        onClipStarted = {},
        normalise = { it },
    )

    @Test
    fun `sentences are spoken one clip each, in order`() = runBlocking {
        val voice = FakeVoice()
        val ear = Ear()
        val s = speaker(voice, ear)
        "Done. The timer is set. Anything else?".chunked(4).forEach { s.text(it) }
        s.finishText()
        assertEquals(ReplySpeaker.Outcome.Played, withTimeout(2_000) { s.await() })
        assertEquals(listOf("Done.", "The timer is set.", "Anything else?"), voice.asked)
        assertEquals(listOf("Done.", "The", "timer", "is", "set.", "Anything", "else?"), ear.heard)
        assertEquals(1, ear.firstAudio)
        assertEquals("Done. The timer is set. Anything else?", s.spoken())
    }

    @Test
    fun `a streaming voice gets one clip fed whole words`() = runBlocking {
        val voice = FakeVoice(streamsText = true)
        val ear = Ear()
        val s = speaker(voice, ear)
        listOf("It is ", "\$1,2", "84.17 to", "day.").forEach { s.text(it) }
        s.finishText()
        assertEquals(ReplySpeaker.Outcome.Played, withTimeout(2_000) { s.await() })
        assertEquals(listOf("<stream>"), voice.asked)
        assertEquals(listOf("It", "is", "\$1,284.17", "today."), ear.heard)
    }

    @Test
    fun `a voice that never starts fails at the first-audio deadline`() = runBlocking {
        val voice = FakeVoice(silentFor = setOf("Hello."))
        val s = speaker(voice, Ear(), timeoutMs = 150)
        s.say("Hello.")
        assertEquals(ReplySpeaker.Outcome.Failed("first_audio_timeout"), withTimeout(2_000) { s.await() })
    }

    @Test
    fun `a voice that fails mid-reply is a failure, after what was already heard`() = runBlocking {
        val voice = FakeVoice(failFor = setOf("Second part."))
        val ear = Ear()
        val s = speaker(voice, ear)
        s.text("First part. Second part. Third part.")
        s.finishText()
        assertEquals(ReplySpeaker.Outcome.Failed("tts_internal"), withTimeout(2_000) { s.await() })
        assertEquals("First part.", s.spoken())
    }

    @Test
    fun `an interruption stops the reply and history keeps only what was heard`() = runBlocking {
        val voice = FakeVoice(frameDelayMs = 60)
        val ear = Ear()
        val s = speaker(voice, ear)
        s.text("One two three four five six. Never heard at all.")
        s.finishText()
        while (ear.heard.size < 2) delay(10)
        s.interrupt()
        assertEquals(ReplySpeaker.Outcome.Interrupted, withTimeout(2_000) { s.await() })
        assertTrue(ear.heard.size < 6)
        assertEquals("One two three four five six.", s.spoken())
    }

    @Test
    fun `a refused stream replays the reply sentence by sentence`() = runBlocking {
        val voice = FakeVoice(streamsText = true, refuseStream = true)
        val ear = Ear()
        val s = speaker(voice, ear)
        s.text("Short one. Another. ")
        s.finishText()
        assertEquals(ReplySpeaker.Outcome.Played, withTimeout(2_000) { s.await() })
        assertEquals(listOf("<stream>", "Short one.", "Another."), voice.asked)
        assertEquals(listOf("Short", "one.", "Another."), ear.heard)
    }
}
