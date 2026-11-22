@file:Suppress("MaxLineLength") // test fixtures: invented wire frames read best on one line

package com.kevin.legion.service.cascade

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The laptop voice's kept filler lines (2026-11-20): honest [LaptopVoiceConnection.inHand] and a
 * real [LaptopVoiceConnection.warm], one render at a time and only while no real speech is open.
 * Against an in-memory fake of the service; invented audio only.
 */
@RunWith(RobolectricTestRunner::class)
class LaptopVoiceWarmTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val config = LaptopVoiceConfig("http://127.0.0.1:8765", "invented-token")
    private val lines = Backchannel.LINES.map { SpokenText.forSpeech(it) }.toSet()
    private val pcm = ByteArray(9_600) { 3 } // 200 ms at 24 kHz

    @After
    fun tearDown() = scope.cancel()

    /** The fake service: answers `ready`; renders `tts.speak` only when [auto] (else the test does). */
    private inner class Fake(var auto: Boolean = false) : WireConnector {
        val sent = Collections.synchronizedList(mutableListOf<JSONObject>())
        lateinit var phone: WireListener
        @Volatile var closed = false

        override fun connect(url: String, headers: List<Pair<String, String>>, listener: WireListener): WireSocket {
            phone = listener
            return object : WireSocket {
                override fun sendText(text: String): Boolean {
                    if (closed) return false
                    val m = JSONObject(text)
                    sent.add(m)
                    if (m.getString("type") == "hello") {
                        phone.onText(
                            JSONObject().put("type", "ready").put("protocol", 1)
                                .put("stt", JSONObject().put("sample_rate", 16000))
                                .put("tts", JSONObject().put("sample_rate", 24000).put("model", "m").put("default_voice", "v"))
                                .toString(),
                        )
                    }
                    if (auto && m.getString("type") == "tts.speak") render(m.getInt("id"))
                    return true
                }

                override fun sendBinary(bytes: ByteArray): Boolean = !closed

                override fun close(code: Int, reason: String) {
                    closed = true
                }
            }
        }

        fun render(id: Int) {
            phone.onBinary(LaptopVoiceConnection.frame(LaptopVoiceConnection.KIND_TTS, id, pcm))
            phone.onText(JSONObject().put("type", "tts.done").put("id", id).put("audio_ms", 200).toString())
        }

        fun of(type: String) = synchronized(sent) { sent.filter { it.getString("type") == type } }
        fun speaks() = of("tts.speak")
    }

    private suspend fun open(fake: Fake, kept: MutableMap<String, ByteArray> = ConcurrentHashMap()): LaptopVoiceConnection {
        val c = LaptopVoiceConnection(fake, config, "legion-phone/test", scope, keepLines = lines, kept = kept)
        assertEquals(LaptopVoiceConnection.OpenResult.Ready, c.open(1_000))
        return c
    }

    private suspend fun until(ms: Long = 3_000, what: () -> Boolean) {
        withTimeout(ms) { while (!what()) delay(10) }
    }

    @Test
    fun `inHand is true only for a kept line already saved for this voice`() = runBlocking<Unit> {
        val fake = Fake(auto = true)
        val c = open(fake)
        assertFalse(c.inHand("Mm."))
        assertFalse(c.inHand("An invented sentence."))
        val clip = c.speak("Mm.")
        withTimeout(1_000) { clip.frames.toList() }
        assertTrue(clip.outcome() is TtsOutcome.Done)
        assertTrue(c.inHand("Mm."))
        assertFalse(c.inHand("An invented sentence."))
    }

    @Test
    fun `with nothing saved a turn sends no live filler request`() = runBlocking<Unit> {
        val fake = Fake(auto = true)
        val c = open(fake)
        val chosen = Backchannel.choose(RecentLines(2), 0, { w, t -> c.inHandTake(SpokenText.forSpeech(w), t) }) { _, _ -> 300L }
        assertNull(chosen)
        assertEquals(0, fake.speaks().size)
    }

    @Test
    fun `warm fetches unsaved lines one at a time, saves them, and plays none`() = runBlocking<Unit> {
        val fake = Fake()
        val kept = ConcurrentHashMap<String, ByteArray>()
        val c = open(fake, kept)
        assertEquals(2, c.warm(listOf("Mm.", "Right.", "Not a kept line.")))
        until { fake.speaks().size == 1 }
        delay(100)
        assertEquals("one render in flight at a time", 1, fake.speaks().size)
        fake.render(fake.speaks()[0].getInt("id"))
        until { fake.speaks().size == 2 }
        fake.render(fake.speaks()[1].getInt("id"))
        until { c.inHand("Mm.") && c.inHand("Right.") }
        assertEquals(listOf("Mm.", "Right."), fake.speaks().map { it.getString("text") })
        assertEquals(2, kept.size)
        // Asked again, nothing is fetched twice.
        assertEquals(0, c.warm(listOf("Mm.", "Right.")))
    }

    @Test
    fun `after warm a backchannel plays from memory inside its window, with no request`() = runBlocking<Unit> {
        val fake = Fake(auto = true)
        val c = open(fake)
        c.warm(lines)
        until(10_000) { lines.all { c.inHand(it) } }
        val before = fake.speaks().size
        val chosen = Backchannel.choose(RecentLines(2), 0, { w, t -> c.inHandTake(SpokenText.forSpeech(w), t) }) { w, t ->
            val clip = c.speakTake(SpokenText.forSpeech(w), t)
            val audio = withTimeout(300) { clip.frames.toList() }
            audio.sumOf { it.size }.toLong() / 48
        }
        assertTrue(chosen != null)
        assertEquals(before, fake.speaks().size)
    }

    @Test
    fun `real speech cancels the warm in flight, goes at once, and warming waits until it is over`() = runBlocking<Unit> {
        val fake = Fake()
        val c = open(fake)
        c.warm(listOf("Mm.", "Right."))
        until { fake.speaks().size == 1 }
        val warmId = fake.speaks()[0].getInt("id")
        val stream = c.openStream()
        // The warm is cancelled and the reply's stream starts at once: one in-flight line is the most it waits behind.
        assertTrue(fake.of("tts.cancel").any { it.getInt("id") == warmId })
        assertTrue(fake.of("tts.stream.start").any { it.getInt("id") == stream.id })
        delay(LaptopVoiceConnection.WARM_RESUME_MS + 300)
        assertEquals("no warm while the reply is open", 1, fake.speaks().size)
        stream.append("An invented answer.")
        stream.finish()
        fake.render(stream.id)
        delay(LaptopVoiceConnection.WARM_RESUME_MS / 2)
        assertEquals("nothing straight after the reply", 1, fake.speaks().size)
        until { fake.speaks().size == 2 }
        // The cancelled line went back to the front of the queue.
        assertEquals("Mm.", fake.speaks()[1].getString("text"))
    }

    @Test
    fun `a line served from memory does not stop warming`() = runBlocking<Unit> {
        val fake = Fake()
        val kept = ConcurrentHashMap<String, ByteArray>()
        val c = open(fake, kept)
        val first = c.speak("Mm.")
        fake.render(first.id)
        until { c.inHand("Mm.") }
        c.warm(listOf("Right."))
        until { fake.speaks().size == 2 }
        val fromMemory = c.speak("Mm.")
        assertArrayEquals(pcm, withTimeout(300) { fromMemory.frames.toList() }.single())
        assertTrue(fake.of("tts.cancel").isEmpty())
    }

    @Test
    fun `held for the greeting, nothing is fetched until released`() = runBlocking<Unit> {
        val fake = Fake()
        val c = open(fake)
        c.holdWarm(true)
        c.warm(listOf("Mm."))
        delay(300)
        assertEquals(0, fake.speaks().size)
        c.holdWarm(false)
        delay(LaptopVoiceConnection.WARM_RESUME_MS / 2)
        assertEquals(0, fake.speaks().size)
        until { fake.speaks().size == 1 }
    }

    @Test
    fun `warm on a dropped connection sends nothing`() = runBlocking<Unit> {
        val fake = Fake()
        val c = open(fake)
        fake.closed = true
        fake.phone.onClosed(1006, "gone")
        assertEquals(0, c.warm(listOf("Mm.")))
        delay(200)
        assertEquals(0, fake.speaks().size)
    }
}
