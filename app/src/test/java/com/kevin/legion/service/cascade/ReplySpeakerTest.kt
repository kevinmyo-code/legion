@file:Suppress("MaxLineLength", "ReturnCount", "EmptyFunctionBlock") // test fixtures: invented JSON and wire frames read best on one line

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
import org.junit.Assert.assertFalse
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
        var fillerAudio = 0
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
        onFirstAudio = { _, filler -> if (filler) ear.fillerAudio++ else ear.firstAudio++ },
        onClipStarted = {},
        normalise = { it },
    )

    @Test
    fun `sentences are spoken one clip each, in order`() = runBlocking<Unit> {
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
    fun `a streaming voice gets one clip fed whole words`() = runBlocking<Unit> {
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
    fun `a long streamed reply goes on in a new clip before the service's per-id cap`() = runBlocking<Unit> {
        val voice = FakeVoice(streamsText = true)
        val ear = Ear()
        val s = speaker(voice, ear)
        val words = List(400) { "word$it" } // about 3,000 chars
        words.forEach { s.text("$it ") }
        s.finishText()
        assertEquals(ReplySpeaker.Outcome.Played, withTimeout(5_000) { s.await() })
        assertTrue("expected several clips, got ${voice.asked}", voice.asked.count { it == "<stream>" } >= 3)
        assertEquals(words, ear.heard)
    }

    @Test
    fun `a voice that never starts fails at the first-audio deadline`() = runBlocking<Unit> {
        val voice = FakeVoice(silentFor = setOf("Hello."))
        val s = speaker(voice, Ear(), timeoutMs = 150)
        s.say("Hello.")
        assertEquals(ReplySpeaker.Outcome.Failed("first_audio_timeout"), withTimeout(2_000) { s.await() })
    }

    @Test
    fun `a voice that fails mid-reply is a failure, after what was already heard`() = runBlocking<Unit> {
        val voice = FakeVoice(failFor = setOf("Second part."))
        val ear = Ear()
        val s = speaker(voice, ear)
        s.text("First part. Second part. Third part.")
        s.finishText()
        assertEquals(ReplySpeaker.Outcome.Failed("tts_internal"), withTimeout(2_000) { s.await() })
        assertEquals("First part.", s.spoken())
    }

    @Test
    fun `an interruption stops the reply and history keeps only what was heard`() = runBlocking<Unit> {
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
    fun `a short scripted line is one whole clip even on a voice that streams`() = runBlocking<Unit> {
        val voice = FakeVoice(streamsText = true)
        val ear = Ear()
        val s = speaker(voice, ear)
        s.say("I'm here.")
        assertEquals(ReplySpeaker.Outcome.Played, withTimeout(2_000) { s.await() })
        assertEquals(listOf("I'm here."), voice.asked)
        assertEquals("I'm here.", s.spoken())
    }

    @Test
    fun `a filler plays first, is not part of what the reply said, and has its own first-audio report`() = runBlocking<Unit> {
        val voice = FakeVoice(streamsText = true)
        val ear = Ear()
        val s = speaker(voice, ear)
        assertTrue(s.filler("One moment."))
        // The filler's audio is out before the answer's text arrives (from memory, it always is).
        withTimeout(2_000) { while (ear.fillerAudio == 0) delay(5) }
        s.text("Your balance is four dollars. ")
        assertFalse("no second filler once the answer has text", s.filler("One moment."))
        s.finishText()
        assertEquals(ReplySpeaker.Outcome.Played, withTimeout(2_000) { s.await() })
        assertEquals(listOf("One moment.", "<stream>"), voice.asked)
        assertEquals(listOf("One", "moment.", "Your", "balance", "is", "four", "dollars."), ear.heard)
        assertEquals("Your balance is four dollars.", s.spoken())
        assertEquals(1, ear.fillerAudio)
        assertEquals(1, ear.firstAudio)
    }

    @Test
    fun `a filler whose audio comes after the answer's text is dropped unheard`() = runBlocking<Unit> {
        // Every frame is late: the answer's text is in before the filler's first audio.
        val voice = FakeVoice(frameDelayMs = 150)
        val ear = Ear()
        val s = speaker(voice, ear, timeoutMs = 2_000)
        assertTrue(s.filler("One moment."))
        s.text("Invented answer. ")
        s.finishText()
        assertEquals(ReplySpeaker.Outcome.Played, withTimeout(3_000) { s.await() })
        assertEquals(listOf("Invented", "answer."), ear.heard)
        assertEquals(0, ear.fillerAudio)
        assertEquals(1, ear.firstAudio)
        assertEquals("Invented answer.", s.spoken())
    }

    @Test
    fun `the filler fires on the tool names the model calls now, and only from memory`() {
        for (name in listOf("look_up", "ask", "day", "ask_engine", "ask_mail")) {
            assertTrue(name, ScriptedLines.fillerDue(listOf(name), anyText = false, fillerPlayed = false))
        }
        // Writes and instant local tools: the answer is the first sound.
        for (name in listOf("phone", "car", "manage_item", "end_conversation", "money", "goal")) {
            assertFalse(name, ScriptedLines.fillerDue(listOf(name), anyText = false, fillerPlayed = false))
        }
        assertTrue(ScriptedLines.fillerDue(listOf("phone", "look_up"), anyText = false, fillerPlayed = false))
        assertTrue(ScriptedLines.fillerNow(due = true, inHand = true, bargeIn = true))
        assertFalse("a line not in memory is skipped and warmed", ScriptedLines.fillerNow(due = true, inHand = false, bargeIn = true))
        assertFalse(ScriptedLines.fillerNow(due = false, inHand = true, bargeIn = true))
        // Half-duplex does not listen while a filler counts as playing: his words would be lost.
        assertFalse(ScriptedLines.fillerNow(due = true, inHand = true, bargeIn = false))
    }

    @Test
    fun `the filler set is the five approved claim-nothing lines, all kept in memory`() {
        // Kevin, 2026-11-16: three more beside the first two, his own words "on it" and "a moment".
        assertEquals(
            listOf("One moment.", "Just a moment.", "On it.", "Checking.", "Let me look."),
            ScriptedLines.FILLERS,
        )
        assertTrue(ScriptedLines.KEEP.containsAll(ScriptedLines.FILLERS))
        assertTrue(ScriptedLines.KEEP.containsAll(AckLines.DEFAULT))
    }

    @Test
    fun `the filler never says the same line twice running`() {
        val lines = AckLines(ScriptedLines.FILLERS)
        val said = List(12) { lines.next() }
        for (k in 1 until said.size) assertTrue(said[k] != said[k - 1])
        assertTrue(said.all { it in ScriptedLines.FILLERS })
    }

    @Test
    fun `a filler is due only for a slow tool, before any text, once`() {
        assertTrue(ScriptedLines.fillerDue(listOf("ask_body"), anyText = false, fillerPlayed = false))
        assertFalse(ScriptedLines.fillerDue(listOf("get_current_time"), anyText = false, fillerPlayed = false))
        assertFalse(ScriptedLines.fillerDue(listOf("ask_engine"), anyText = true, fillerPlayed = false))
        assertFalse(ScriptedLines.fillerDue(listOf("ask_mail"), anyText = false, fillerPlayed = true))
        // Scripted, short, kept in memory by both voices, and never an outcome.
        for (line in ScriptedLines.FILLERS) {
            assertTrue(line in ScriptedLines.KEEP)
            for (verb in listOf("done", "sent", "set", "added", "booked", "found", "checked")) {
                assertFalse("$line must not claim $verb", line.lowercase().contains(verb))
            }
        }
    }

    @Test
    fun `a refused stream replays the reply sentence by sentence`() = runBlocking<Unit> {
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
