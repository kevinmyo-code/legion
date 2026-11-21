package com.kevin.legion.service.cascade

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The streamed utterance's body, byte for byte against the engine's parser
 * (`server/agents/voice/streaming.py`: `HEADER = struct.Struct(">cI")`, types M/A/E/T, a frame at
 * most 64 KiB, an empty audio frame refused, `E` carrying `{"bytes": total}`). [parse] below is that
 * reader's rule, line for line, so a frame the phone writes is a frame the engine reads.
 */
class EngineFramesTest {

    /** The engine's `FrameReader.next` loop, on a whole body. */
    private fun parse(body: ByteArray): List<Pair<Char, ByteArray>> {
        val buf = ByteBuffer.wrap(body)
        val out = mutableListOf<Pair<Char, ByteArray>>()
        while (buf.remaining() > 0) {
            assertTrue("a header needs 5 bytes", buf.remaining() >= HEADER)
            val kind = buf.get().toInt().toChar()
            val length = buf.int
            assertTrue("unknown type $kind", kind in "MAET")
            assertTrue("over the engine's frame cap", length <= EngineFrames.MAX_FRAME_BYTES)
            assertTrue("an audio frame carries at least one byte", kind != 'A' || length > 0)
            val payload = ByteArray(length)
            buf.get(payload)
            out.add(kind to payload)
        }
        return out
    }

    @Test
    fun `a metadata frame is M, a big-endian length and the JSON`() {
        val json = """{"client":"phone","contract_version":1}"""
        val frame = EngineFrames.meta(json)
        val bytes = json.toByteArray(Charsets.UTF_8)
        assertEquals('M'.code.toByte(), frame[0])
        assertArrayEquals(byteArrayOf(0, 0, 0, bytes.size.toByte()), frame.copyOfRange(1, HEADER))
        assertArrayEquals(bytes, frame.copyOfRange(HEADER, frame.size))
    }

    @Test
    fun `a length past 255 is written in all four bytes, most significant first`() {
        val pcm = ByteArray(300 + 70_000) { (it % 251).toByte() }
        val frames = EngineFrames.audio(pcm, 300, 70_000)
        assertEquals("70,000 bytes is two frames under the 64 KiB cap", 2, frames.size)
        val first = frames[0]
        assertArrayEquals(byteArrayOf('A'.code.toByte(), 0, 1, 0, 0), first.copyOfRange(0, HEADER))
        val parsed = parse(frames[0] + frames[1])
        assertEquals(listOf(65_536, 4_464), parsed.map { it.second.size })
        assertArrayEquals(pcm.copyOfRange(300, 300 + 70_000), parsed[0].second + parsed[1].second)
    }

    @Test
    fun `an empty slice is no frame at all, never an empty audio frame the engine refuses`() {
        assertTrue(EngineFrames.audio(ByteArray(10), 4, 0).isEmpty())
    }

    @Test
    fun `the end frame names the audio total as the engine's JSON reads it`() {
        val (kind, payload) = parse(EngineFrames.end(64_000)).single()
        assertEquals('E', kind)
        assertEquals("""{"bytes":64000}""", String(payload, Charsets.UTF_8))
    }

    @Test
    fun `a whole utterance parses in the engine's order and its total matches the audio sent`() {
        val pcm = ByteArray(3_200) { 7 }
        val body = EngineFrames.meta("{}") +
            EngineFrames.audio(pcm, 0, 1_600).reduce { a, b -> a + b } +
            EngineFrames.audio(pcm, 1_600, 1_600).reduce { a, b -> a + b } +
            EngineFrames.end(3_200)
        val frames = parse(body)
        assertEquals(listOf('M', 'A', 'A', 'E'), frames.map { it.first })
        val audio = frames.filter { it.first == 'A' }.sumOf { it.second.size }
        assertEquals("""{"bytes":$audio}""", String(frames.last().second, Charsets.UTF_8))
    }

    private companion object {
        const val HEADER = 5
    }
}
