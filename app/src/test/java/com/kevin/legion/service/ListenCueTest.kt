package com.kevin.legion.service

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** F2 (voice cost audit 2026-11-10): the scripted opening line's list, picker and clip format. */
@RunWith(RobolectricTestRunner::class)
class ListenCueTest {

    private fun linesJson(): String {
        val f = listOf("src/main/assets/listen_cues/lines.json", "app/src/main/assets/listen_cues/lines.json")
            .map(::File).first { it.isFile }
        return f.readText()
    }

    private val lines by lazy { ListenCue.parseLines(linesJson()) }
    private val morning = LocalDateTime.of(2026, 11, 10, 8, 30)

    @Test
    fun `the one list holds Kevin's 25 lines, distinct, never sir`() {
        assertEquals(25, lines.size)
        assertEquals(lines.size, lines.map { it.id }.toSet().size)
        assertEquals(18, lines.count { it.window == null })
        assertTrue(lines.none { it.text.contains("sir", ignoreCase = true) })
        assertTrue(lines.all { Regex("[a-z0-9_]+").matches(it.id) })
    }

    @Test
    fun `the clips' voice is data in the list, so a re-render in another voice needs no code`() {
        assertEquals("Puck", ListenCue.parseVoice(linesJson()))
        assertEquals("local-voice-1", ListenCue.parseVoice("""{"voice":"local-voice-1","lines":[]}"""))
        assertEquals("Puck", ListenCue.parseVoice("""{"lines":[]}"""))
    }

    @Test
    fun `the first open in a window plays a time-of-day line, once per window per day`() {
        val first = ListenCue.pick(lines, morning, ListenCue.State(), Random(1))
        assertNotNull("the first open of the morning gets a morning line", first.line.window)
        assertTrue(first.line.window!!.contains(morning.toLocalTime()))
        var state = first.state
        repeat(50) {
            val p = ListenCue.pick(lines, morning.plusMinutes(it.toLong()), state, Random(it))
            assertNull("a morning line played twice in one morning", p.line.window)
            state = p.state
        }
        // The afternoon is a new window the same day.
        val afternoon = ListenCue.pick(lines, morning.withHour(13), state, Random(7))
        assertEquals("12:00-16:59", afternoon.line.window?.key)
        // The next morning is a new day.
        val nextDay = ListenCue.pick(lines, morning.plusDays(1), afternoon.state, Random(3))
        assertEquals("05:00-11:59", nextDay.line.window?.key)
    }

    @Test
    fun `never the same line twice in a row`() {
        var state = ListenCue.State()
        var last: String? = null
        repeat(500) {
            val p = ListenCue.pick(lines, morning, state, Random(it * 31 + 5))
            assertNotEquals(last, p.line.id)
            last = p.line.id
            state = p.state
        }
    }

    @Test
    fun `a line outside its window never plays`() {
        val night = LocalDateTime.of(2026, 11, 10, 2, 15)
        val p = ListenCue.pick(lines, night, ListenCue.State(), Random(2))
        assertEquals("up_late", p.line.id)
        repeat(100) {
            val q = ListenCue.pick(lines, night, p.state, Random(it))
            assertTrue(q.line.window == null)
        }
    }

    @Test
    fun `a 24 kHz mono 16-bit wav is accepted and its samples returned`() {
        val samples = byteArrayOf(1, 2, 3, 4)
        assertEquals(samples.toList(), ListenCue.wavPcm(wav(samples, 24_000, 1))!!.toList())
    }

    @Test
    fun `any other wav format is refused, so it never plays at the wrong speed`() {
        assertNull(ListenCue.wavPcm(wav(byteArrayOf(1, 2), 16_000, 1)))
        assertNull(ListenCue.wavPcm(wav(byteArrayOf(1, 2, 3, 4), 24_000, 2)))
        assertNull(ListenCue.wavPcm(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `a clip is queued in reply-sized chunks so the mic gate sees it playing`() {
        val pcm = ByteArray(24_000) { it.toByte() }
        val parts = ListenCue.chunks(pcm)
        assertEquals(5, parts.size)
        assertTrue(parts.all { it.size <= ListenCue.CHUNK_BYTES })
        assertEquals(pcm.toList(), parts.flatMap { it.toList() })
        assertEquals(1, ListenCue.chunks(ByteArray(10)).size)
    }

    @Test
    fun `the earcon is short 24 kHz PCM16`() {
        val pcm = ListenCue.earconPcm()
        val seconds = pcm.size / 2.0 / ListenCue.SAMPLE_RATE
        assertTrue("earcon is ${seconds}s", seconds in 0.15..0.4)
        assertEquals(0, pcm.size % 2)
    }

    private fun wav(pcm: ByteArray, rate: Int, channels: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        fun le16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
        out.write("RIFF".toByteArray()); out.write(le32(36 + pcm.size)); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(le32(16)); out.write(le16(1)); out.write(le16(channels))
        out.write(le32(rate)); out.write(le32(rate * channels * 2)); out.write(le16(channels * 2)); out.write(le16(16))
        out.write("data".toByteArray()); out.write(le32(pcm.size)); out.write(pcm)
        return out.toByteArray()
    }
}
