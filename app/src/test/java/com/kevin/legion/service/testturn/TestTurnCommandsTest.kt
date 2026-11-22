@file:Suppress("MagicNumber", "MaxLineLength") // invented clips and clocks; fixtures read best on one line

package com.kevin.legion.service.testturn

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The adb door's commands, the test mic's WAV and pacing, and the test session's watchdog clock. */
class TestTurnCommandsTest {

    private class FakeHost : TestTurns.Host {
        val turns = mutableListOf<Triple<String, String, TestWrites>>()
        var clips = 0
        var ended = 0
        var refuse: String? = null
        override fun status() = mapOf<String, Any>("conversation" to "none", "turns_answered" to 0)
        override fun turn(id: String, text: String, writes: TestWrites): String? {
            turns.add(Triple(id, text, writes))
            return refuse
        }
        override fun audio(id: String, pcm: ByteArray, writes: TestWrites, barge: Boolean): String? {
            clips++
            return refuse
        }
        override fun end(reason: String) {
            ended++
        }
    }

    private fun handle(method: String, arg: String? = null, enabled: Boolean = true, host: TestTurns.Host? = FakeHost(), clip: ByteArray? = null) =
        TestTurnCommands.handle(method, arg, "case1", TestWrites.NONE, enabled, host) { clip }

    @Test
    fun `status answers even when off, and says so`() {
        val off = handle("status", enabled = false)
        assertEquals(true, off["ok"])
        assertEquals(false, off["enabled"])
        assertNull(off["conversation"])
        assertEquals(true, off["allow_tools"])
        val on = handle("status")
        assertEquals("none", on["conversation"])
        assertEquals(true, on["service"])
    }

    @Test
    fun `everything else is refused while the switch is off`() {
        for (m in listOf("turn", "audio", "end")) {
            val r = handle(m, "x", enabled = false)
            assertEquals(false, r["ok"])
            assertTrue((r["error"] as String).startsWith("disabled"))
        }
    }

    @Test
    fun `no service, no text, bad clip and unknown method are refused in words`() {
        assertEquals("service_not_running", handle("turn", "hi", host = null)["error"])
        assertEquals("no_text", handle("turn", "  ")["error"])
        assertTrue((handle("audio", "a.wav", clip = null)["error"] as String).startsWith("bad_clip"))
        assertEquals("unknown_method", handle("format_phone", "x")["error"])
    }

    @Test
    fun `a turn reaches the host with its case and write rule, and a host refusal comes back`() {
        val host = FakeHost()
        assertEquals(true, TestTurnCommands.handle("turn", " what's due ", "c9", TestWrites.ALL, true, host) { null }["ok"])
        assertEquals(Triple("c9", "what's due", TestWrites.ALL), host.turns.single())
        host.refuse = "real_conversation"
        assertEquals("real_conversation", TestTurnCommands.handle("turn", "x", "c9", TestWrites.NONE, true, host) { null }["error"])
        TestTurnCommands.handle("end", null, "c9", TestWrites.NONE, true, host) { null }
        assertEquals(1, host.ended)
    }

    @Test
    fun `a 16 kHz mono 16-bit WAV parses, anything else does not`() {
        val pcm = ByteArray(640) { (it % 7).toByte() }
        assertArrayEquals(pcm, TestMic.parseWav(wav(pcm, 16_000, 1)))
        assertNull(TestMic.parseWav(wav(pcm, 24_000, 1)))
        assertNull(TestMic.parseWav(wav(pcm, 16_000, 2)))
        assertNull(TestMic.parseWav(ByteArray(10)))
        assertNull(TestMic.parseWav("not a wav file at all, just some text bytes....".toByteArray()))
    }

    @Test
    fun `the test mic feeds silence, then a queued clip, in 100 ms chunks`() = runBlocking {
        val mic = TestMic()
        val clip = ByteArray(TestMic.CHUNK_BYTES * 2 + 100) { 7 }
        assertTrue(mic.queue(clip))
        val seen = mutableListOf<ByteArray>()
        val job = launch { mic.play { b, n -> synchronized(seen) { seen.add(b.copyOf(n)) } } }
        withTimeout(5_000) { while (synchronized(seen) { seen.size } < 4) kotlinx.coroutines.delay(20) }
        job.cancel()
        val chunks = synchronized(seen) { seen.toList() }
        assertTrue(chunks.all { it.size == TestMic.CHUNK_BYTES })
        assertTrue(chunks[0].all { it == 7.toByte() })
        assertTrue(chunks[2].take(100).all { it == 7.toByte() } && chunks[2].drop(100).all { it == 0.toByte() })
        assertTrue(chunks[3].all { it == 0.toByte() })
    }

    @Test
    fun `audio files are only plain wav names in the app's own folder`() {
        val bad = listOf("../x.wav", "a/b.wav", "x.mp3", "", "x".repeat(90) + ".wav")
        assertTrue(bad.none { TestTurns.safeName(it) })
        assertTrue(TestTurns.safeName("due_open.wav"))
    }

    @Test
    fun `a test session expires when idle or too long open`() {
        val s = TestSession("c1", TestWrites.NONE, openedAtMs = 0)
        assertNull(s.expired(now = 1_000))
        assertEquals("idle", s.expired(now = TestTurns.IDLE_CLOSE_MS + 1))
        s.touch("c2", TestWrites.ALL, now = TestTurns.MAX_OPEN_MS - 10)
        assertEquals("c2", s.id)
        assertEquals(TestWrites.ALL, s.writes)
        assertNull(s.expired(now = TestTurns.MAX_OPEN_MS - 5))
        assertEquals("max_open", s.expired(now = TestTurns.MAX_OPEN_MS + 1))
    }

    private fun wav(pcm: ByteArray, rate: Int, channels: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        out.write(h.array())
        out.write(pcm)
        return out.toByteArray()
    }
}
